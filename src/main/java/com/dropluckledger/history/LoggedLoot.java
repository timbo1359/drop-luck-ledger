package com.dropluckledger.history;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One loot of a boss as the drop log kept it: every item, tracked or not, with the kill number it came on.
 */
public final class LoggedLoot
{
	/**
	 * One item of the loot, named as the game names it.
	 */
	public static final class Item
	{
		public final String name;
		public final int qty;

		public Item(String name, int qty)
		{
			this.name = name;
			this.qty = qty;
		}
	}

	/**
	 * Epoch milliseconds when it was received.
	 */
	public final long t;
	public final String bossId;
	/**
	 * The ledger's kill number it came on.
	 */
	public final int kill;
	public final List<Item> items;
	/**
	 * For items several bosses drop: the other bosses' kill counts when it came (null when none of its items is shared).
	 */
	public final Map<String, Integer> snap;

	public LoggedLoot(long t, String bossId, int kill, List<Item> items, Map<String, Integer> snap)
	{
		this.t = t;
		this.bossId = bossId;
		this.kill = kill;
		this.items = Collections.unmodifiableList(new ArrayList<>(items));
		this.snap = snap == null || snap.isEmpty() ? null : Collections.unmodifiableMap(snap);
	}
}
