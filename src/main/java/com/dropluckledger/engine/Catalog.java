package com.dropluckledger.engine;

import com.dropluckledger.engine.Data.Boss;
import com.dropluckledger.engine.Engine.ItemMeta;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Catalogue helpers shared by the panel and the capture code: which items a boss tracks by default, what kind of item each is,
 * which bosses list the same collection-log item.  Port of the equivalent helpers in the web app.
 */
public final class Catalog
{
	private Catalog()
	{
	}

	public static final List<String> CAT_ORDER = Arrays.asList("Raid-style boss", "Desert Treasure II", "God Wars Dungeon", "Varlamore",
		"Slayer", "Dragons", "Wilderness", "Dagannoth Kings", "Minigame", "Minigame boss", "Skilling boss", "Standalone", "Misc",
		"Minor boss", "Clue boss");

	/**
	 * Bosses in display order (category order, then name).
	 */
	public static List<Boss> ordered(Data d)
	{
		List<Boss> l = new ArrayList<>(d.bosses);
		l.sort(Comparator.<Boss>comparingInt(b -> CAT_ORDER.indexOf(b.cat)).thenComparing(b -> b.name, String.CASE_INSENSITIVE_ORDER));
		return l;
	}

	/**
	 * The items shown by default: the collection-log items, the uniques and the pets that are not hidden; uniques first, then pets.
	 */
	public static List<String> defaultTracked(Boss b)
	{
		Map<String, ItemMeta> m = b.meta();
		Set<String> log = b.logSet();
		List<String> names = new ArrayList<>();
		for (Map.Entry<String, ItemMeta> e : m.entrySet())
		{
			ItemMeta im = e.getValue();
			if (!im.hidden && (log.contains(e.getKey()) || im.isUnique() || im.isPet()))
			{
				names.add(e.getKey());
			}
		}
		// stable sort by rank (JavaScript's sort is stable too)
		names.sort(Comparator.comparingInt(n -> rank(m.get(n))));
		return names;
	}

	private static int rank(ItemMeta m)
	{
		return m.isUnique() ? 0 : m.isPet() ? 1 : 2;
	}

	/**
	 * "unique", "pet" or "other".
	 */
	public static String kindOf(Boss b, String name)
	{
		ItemMeta m = b.meta().get(name);
		if (m == null)
		{
			return "other";
		}
		return m.isPet() ? "pet" : m.isUnique() ? "unique" : "other";
	}

	/**
	 * Item name to the ids of the bosses that list it in their collection log (in the order of {@link #ordered}).
	 */
	public static Map<String, List<String>> logIndex(Data d)
	{
		Map<String, List<String>> idx = new LinkedHashMap<>();
		for (Boss b : ordered(d))
		{
			for (String n : b.logSet())
			{
				idx.computeIfAbsent(n, k -> new ArrayList<>()).add(b.id);
			}
		}
		return idx;
	}

	/**
	 * The "any unique" selection: the tracked uniques that are in the collection log, split into those that share the unique table
	 * and those rolled separately (noany).
	 */
	public static final class AnyNames
	{
		public final List<String> names = new ArrayList<>();
		public final List<String> sep = new ArrayList<>();
	}

	public static AnyNames anyNames(Boss b, List<String> tracked)
	{
		Map<String, ItemMeta> m = b.meta();
		Set<String> log = b.logSet();
		AnyNames out = new AnyNames();
		for (String n : tracked)
		{
			ItemMeta im = m.get(n);
			if (im == null || !im.isUnique() || im.hidden || !log.contains(n))
			{
				continue;
			}
			if (im.noany)
			{
				out.sep.add(n);
			}
			else
			{
				out.names.add(n);
			}
		}
		return out;
	}
}
