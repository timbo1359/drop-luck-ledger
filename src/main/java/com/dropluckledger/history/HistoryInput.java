package com.dropluckledger.history;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything gathered from the game's own records, before it is matched against the ledger.
 */
public final class HistoryInput
{
	/**
	 * Copies of an item the player owns.
	 */
	public static final class Owned
	{
		/**
		 * The boss, when the source says exactly which one (the loot tracker does); otherwise null.
		 */
		public final String bossId;
		/**
		 * Collection log page the item was seen on, when the source has pages; otherwise null.
		 */
		public final String page;
		public final String item;
		public final int count;

		Owned(String bossId, String page, String item, int count)
		{
			this.bossId = bossId;
			this.page = page;
			this.item = item;
			this.count = count;
		}
	}

	/**
	 * Activity name as the game or hiscores print it, to the kill count (the largest if several sources have one).
	 */
	public final Map<String, Integer> kc = new LinkedHashMap<>();
	public final List<Owned> owned = new ArrayList<>();
	/**
	 * Where the data came from, for display.
	 */
	public final Set<String> sources = new LinkedHashSet<>();

	public void addKc(String name, int n, String source)
	{
		if (name == null || n <= 0)
		{
			return;
		}
		kc.merge(name, n, Math::max);
		sources.add(source);
	}

	public void addOwned(String bossId, String page, String item, int count, String source)
	{
		if (item == null || count <= 0)
		{
			return;
		}
		owned.add(new Owned(bossId, page, item, count));
		sources.add(source);
	}

	public boolean isEmpty()
	{
		return kc.isEmpty() && owned.isEmpty();
	}
}
