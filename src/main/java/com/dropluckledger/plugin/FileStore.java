package com.dropluckledger.plugin;

import com.dropluckledger.ledger.LedgerStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import net.runelite.client.util.Filepath;

/**
 * One text file in the plugin's data folder.  Every path goes through RuneLite's {@link Filepath}, so the plugin cannot write
 * anywhere else.  Writes go to a temporary file first and are moved over the real one, so a crash never leaves half a file.
 */
public final class FileStore implements LedgerStore
{
	/**
	 * A ledger file bigger than this is not read (it is not a ledger).
	 */
	static final long MAX_BYTES = 64L * 1024 * 1024;

	private final Filepath dir;
	private final String name;
	private boolean backedUp;

	/**
	 * @param dir  the folder (created on the first write)
	 * @param name the file name inside it, one path segment
	 */
	public FileStore(Filepath dir, String name)
	{
		this.dir = dir;
		this.name = name;
	}

	/**
	 * File name of an account's ledger.
	 */
	public static String ledgerName(long accountHash)
	{
		return "ledger-" + accountHash + ".json";
	}

	@Override
	public String read() throws IOException
	{
		Filepath file = dir.joinSegment(name);
		if (!file.isFile())
		{
			return null;
		}
		return readText(file);
	}

	/**
	 * Reads a whole UTF-8 text file.
	 */
	public static String readText(Filepath file) throws IOException
	{
		if (file.size() > MAX_BYTES)
		{
			throw new IOException("The file is too large to be a ledger backup");
		}
		try (InputStream in = file.openInputStream())
		{
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * Writes a whole UTF-8 text file (used for backups the player chose the location of).
	 */
	public static void writeText(Filepath file, String text) throws IOException
	{
		file.write(text, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
	}

	@Override
	public synchronized void write(String json) throws IOException
	{
		dir.createDirectories();
		Filepath target = dir.joinSegment(name);
		Filepath temp = dir.joinSegment(name + ".tmp");
		writeText(temp, json);
		if (!backedUp && target.isFile())
		{
			// the file as the last session left it: kept once, in case this session does something the player regrets
			target.copyTo(dir.joinSegment(name + ".bak"), StandardCopyOption.REPLACE_EXISTING);
			backedUp = true;
		}
		try
		{
			temp.moveTo(target, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException ex)
		{
			temp.moveTo(target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	@Override
	public synchronized void quarantine() throws IOException
	{
		Filepath file = dir.joinSegment(name);
		if (file.isFile())
		{
			file.moveTo(dir.joinSegment(name + ".unreadable-" + System.currentTimeMillis()), StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
