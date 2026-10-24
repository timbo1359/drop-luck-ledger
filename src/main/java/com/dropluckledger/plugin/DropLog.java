package com.dropluckledger.plugin;

import com.dropluckledger.history.LoggedLoot;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import net.runelite.client.util.Filepath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every loot the plugin's bosses give, standard drops included, one line per loot in {@code drops-<account>.jsonl}:
 * <pre>{"t":1761040800000,"boss":"zulrah","kill":1234,"items":[["Coins",40000],["Zulrah's scales",120],["Tanzanite fang",1]]}</pre>
 * The ledger keeps only the items the player tracks; this file keeps everything, so an item tracked later gets its earlier copies,
 * with the kills they came on ({@link com.dropluckledger.history.Backfill}).  Lines are only ever added, on the background thread;
 * a line that cannot be read (half written when the computer went off) is skipped.
 */
public final class DropLog
{
	private static final Logger log = LoggerFactory.getLogger(DropLog.class);

	/**
	 * A log bigger than this is not read back (it would take too long and too much memory).
	 */
	static final long MAX_BYTES = 256L * 1024 * 1024;

	private static final class Line
	{
		final Filepath file;
		final String text;

		Line(Filepath file, String text)
		{
			this.file = file;
			this.text = text;
		}
	}

	private final Filepath dir;
	private final Executor executor;
	private final ConcurrentLinkedQueue<Line> queue = new ConcurrentLinkedQueue<>();
	private final Object writing = new Object();
	private volatile Filepath file;

	/**
	 * @param dir      the plugin's data folder
	 * @param executor where the file is written and read
	 */
	public DropLog(Filepath dir, Executor executor)
	{
		this.dir = dir;
		this.executor = executor;
	}

	/**
	 * File name of an account's drop log.
	 */
	public static String fileName(long accountHash)
	{
		return "drops-" + accountHash + ".jsonl";
	}

	/**
	 * The account whose loot is logged from now on, or null for none (logged out, or a world with its own save).
	 */
	public void setAccount(Long accountHash)
	{
		file = accountHash == null ? null : dir.joinSegment(fileName(accountHash));
	}

	/**
	 * Logs one loot.  Any thread: the line is written on the background thread.
	 */
	public void add(LoggedLoot loot)
	{
		Filepath f = file;
		if (f == null || loot.items.isEmpty())
		{
			return;
		}
		queue.add(new Line(f, toJson(loot)));
		executor.execute(this::flush);
	}

	/**
	 * Writes the lines waiting.  Background thread.
	 */
	public void flush()
	{
		synchronized (writing)
		{
			Map<Filepath, StringBuilder> byFile = new LinkedHashMap<>();
			for (Line l = queue.poll(); l != null; l = queue.poll())
			{
				byFile.computeIfAbsent(l.file, k -> new StringBuilder()).append(l.text).append('\n');
			}
			for (Map.Entry<Filepath, StringBuilder> e : byFile.entrySet())
			{
				try
				{
					dir.createDirectories();
					e.getKey().write(e.getValue().toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
				}
				catch (IOException ex)
				{
					log.warn("Could not write to the drop log", ex);
				}
			}
		}
	}

	/**
	 * Every loot logged for a boss, oldest first.  Background thread.
	 */
	public List<LoggedLoot> read(String bossId) throws IOException
	{
		flush();
		Filepath f = file;
		if (f == null || !f.isFile())
		{
			return Collections.emptyList();
		}
		if (f.size() > MAX_BYTES)
		{
			throw new IOException("The drop log is too large to read");
		}
		List<LoggedLoot> out = new ArrayList<>();
		synchronized (writing)
		{
			try (BufferedReader r = f.openBufferedReader())
			{
				for (String line = r.readLine(); line != null; line = r.readLine())
				{
					LoggedLoot l = parse(line);
					if (l != null && l.bossId.equals(bossId))
					{
						out.add(l);
					}
				}
			}
		}
		return out;
	}

	/**
	 * Deletes the account's log, with the lines still waiting for it (the ledger was erased).  Background thread.
	 */
	public void erase() throws IOException
	{
		Filepath f = file;
		if (f == null)
		{
			return;
		}
		synchronized (writing)
		{
			queue.removeIf(l -> l.file.equals(f));
			f.deleteIfExists();
		}
	}

	static String toJson(LoggedLoot loot)
	{
		JsonObject o = new JsonObject();
		o.addProperty("t", loot.t);
		o.addProperty("boss", loot.bossId);
		o.addProperty("kill", loot.kill);
		JsonArray items = new JsonArray();
		for (LoggedLoot.Item it : loot.items)
		{
			JsonArray pair = new JsonArray();
			pair.add(it.name);
			pair.add(it.qty);
			items.add(pair);
		}
		o.add("items", items);
		if (loot.snap != null)
		{
			JsonObject snap = new JsonObject();
			for (Map.Entry<String, Integer> e : loot.snap.entrySet())
			{
				snap.addProperty(e.getKey(), e.getValue());
			}
			o.add("snap", snap);
		}
		return o.toString();
	}

	/**
	 * One line, or null when it cannot be read.
	 */
	static LoggedLoot parse(String line)
	{
		if (line == null || line.trim().isEmpty())
		{
			return null;
		}
		try
		{
			JsonElement root = new JsonParser().parse(line);
			if (!root.isJsonObject())
			{
				return null;
			}
			JsonObject o = root.getAsJsonObject();
			JsonElement boss = o.get("boss");
			JsonElement kill = o.get("kill");
			JsonElement list = o.get("items");
			if (boss == null || !boss.isJsonPrimitive() || kill == null || !kill.isJsonPrimitive() || list == null || !list.isJsonArray())
			{
				return null;
			}
			List<LoggedLoot.Item> items = new ArrayList<>();
			for (JsonElement e : list.getAsJsonArray())
			{
				if (e.isJsonArray() && e.getAsJsonArray().size() >= 2)
				{
					JsonArray pair = e.getAsJsonArray();
					items.add(new LoggedLoot.Item(pair.get(0).getAsString(), pair.get(1).getAsInt()));
				}
			}
			Map<String, Integer> snap = null;
			JsonElement s = o.get("snap");
			if (s != null && s.isJsonObject())
			{
				snap = new LinkedHashMap<>();
				for (Map.Entry<String, JsonElement> e : s.getAsJsonObject().entrySet())
				{
					snap.put(e.getKey(), e.getValue().getAsInt());
				}
			}
			JsonElement t = o.get("t");
			return new LoggedLoot(t != null && t.isJsonPrimitive() ? t.getAsLong() : 0, boss.getAsString(), kill.getAsInt(), items, snap);
		}
		catch (JsonParseException | IllegalStateException | UnsupportedOperationException | NumberFormatException ex)
		{
			log.debug("Skipping a drop log line that could not be read");
			return null;
		}
	}
}
