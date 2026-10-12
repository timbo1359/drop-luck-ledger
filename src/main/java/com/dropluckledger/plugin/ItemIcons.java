package com.dropluckledger.plugin;

import com.dropluckledger.capture.Names;
import com.dropluckledger.plugin.Game.ItemInfo;
import com.dropluckledger.plugin.Game.ItemRef;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds the game's item picture for an item name.
 * <p>
 * Ids come from three places: items seen in loot and on collection log pages, a search of the tradeable items, and (for
 * what is left, mostly pets) a slow scan of the game's item list that runs a few ids per game tick.  What was found is kept
 * between sessions.
 */
public final class ItemIcons
{
	/**
	 * Item ids looked at per game tick by the scan; small enough not to be felt.
	 */
	static final int SCAN_PER_TICK = 100;
	/**
	 * Above the highest item id the game has.
	 */
	static final int SCAN_LIMIT = 40000;

	private final Game game;
	/**
	 * Flattened item name to item id.
	 */
	private final Map<String, Integer> ids = new ConcurrentHashMap<>();
	private final Set<String> searched = ConcurrentHashMap.newKeySet();
	private volatile boolean dirty;

	private Set<String> wanted = Collections.emptySet();
	private int scanNext = -1;
	private long scannedAt;

	public ItemIcons(Game game)
	{
		this.game = game;
	}

	// ------------------------------------------------------------------ what is known

	/**
	 * Remembers the id of an item seen in the game.
	 */
	public void learn(String name, int id)
	{
		if (name == null || id <= 0)
		{
			return;
		}
		String key = Names.flat(name);
		if (!key.isEmpty() && ids.putIfAbsent(key, id) == null)
		{
			dirty = true;
		}
	}

	/**
	 * The item id for a name, or 0 when it is not known.
	 */
	public int id(String name)
	{
		String key = Names.flat(name);
		Integer known = ids.get(key);
		if (known != null)
		{
			return known;
		}
		if (key.isEmpty() || !searched.add(key))
		{
			return 0;
		}
		int best = 0;
		for (ItemRef p : game.searchItems(name))
		{
			if (p.name.equalsIgnoreCase(name.trim()) && (best == 0 || p.id < best))
			{
				best = p.id;
			}
		}
		if (best > 0)
		{
			ids.put(key, best);
			dirty = true;
		}
		return best;
	}

	/**
	 * The item's picture (filled in by the game a moment after it is returned, when {@code whenLoaded} is called), or null
	 * when the id is not known.
	 */
	public BufferedImage image(String name, Runnable whenLoaded)
	{
		int id = id(name);
		return id > 0 ? game.itemImage(id, whenLoaded) : null;
	}

	// ------------------------------------------------------------------ saved between sessions

	/**
	 * True when something was learned since the last {@link #snapshot()}.
	 */
	public boolean isDirty()
	{
		return dirty;
	}

	/**
	 * What to save: flattened name to id, in name order.
	 */
	public synchronized Map<String, Integer> snapshot()
	{
		dirty = false;
		return new TreeMap<>(ids);
	}

	public synchronized long scannedAt()
	{
		return scannedAt;
	}

	public synchronized void restore(Map<String, Integer> saved, long scannedAtMillis)
	{
		for (Map.Entry<String, Integer> e : saved.entrySet())
		{
			if (e.getKey() != null && e.getValue() != null && e.getValue() > 0)
			{
				ids.putIfAbsent(e.getKey(), e.getValue());
			}
		}
		this.scannedAt = scannedAtMillis;
	}

	// ------------------------------------------------------------------ scan

	/**
	 * Starts looking through the game's items for the given names.  Does nothing when a scan is running.
	 *
	 * @param names the item names the ledger knows
	 */
	public synchronized void startScan(Iterable<String> names)
	{
		if (scanNext >= 0)
		{
			return;
		}
		Set<String> want = new HashSet<>();
		for (String n : names)
		{
			String key = Names.flat(n);
			if (!key.isEmpty() && !ids.containsKey(key))
			{
				want.add(key);
			}
		}
		if (want.isEmpty())
		{
			return;
		}
		wanted = want;
		scanNext = 1;
	}

	public synchronized boolean isScanning()
	{
		return scanNext >= 0;
	}

	/**
	 * Looks at the next few item ids.  Call on the client thread, once per game tick.
	 *
	 * @param now the time in milliseconds, kept as the time of the last finished scan
	 * @return true when the scan finished with this call
	 */
	public synchronized boolean scanTick(long now)
	{
		if (scanNext < 0)
		{
			return false;
		}
		int end = Math.min(SCAN_LIMIT, scanNext + SCAN_PER_TICK);
		for (int id = scanNext; id < end && !wanted.isEmpty(); id++)
		{
			ItemInfo c = game.item(id);
			if (c == null || c.name == null || c.noted || c.placeholder)
			{
				continue;
			}
			String key = Names.flat(c.name);
			if (wanted.remove(key))
			{
				if (ids.putIfAbsent(key, id) == null)
				{
					dirty = true;
				}
			}
		}
		scanNext = end;
		if (end >= SCAN_LIMIT || wanted.isEmpty())
		{
			scanNext = -1;
			wanted = Collections.emptySet();
			scannedAt = now;
			dirty = true;
			return true;
		}
		return false;
	}
}
