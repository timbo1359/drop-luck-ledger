package com.dropluckledger.ui;

import com.dropluckledger.engine.Data.Boss;
import com.dropluckledger.engine.Data.Row;
import com.dropluckledger.engine.Engine;
import com.dropluckledger.engine.Luck;
import com.dropluckledger.ledger.BossState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What the panel shows, worked out away from the Swing thread and handed over finished.  Nothing here changes after it is built.
 */
final class Views
{
	private Views()
	{
	}

	/**
	 * One tracked item of a boss with its luck figures.
	 */
	static final class ItemView
	{
		final String name;
		/**
		 * "unique", "pet" or "drop".
		 */
		final String kind;
		final Engine.ItemMeta meta;
		/**
		 * Null when the figures could not be worked out.
		 */
		final Luck.ItemAnalysis a;
		/**
		 * The figures pooled over every boss that drops the item; null unless the player has kills at two or more of them.
		 */
		final Luck.Pool pool;
		/**
		 * Several bosses list this item in their collection log.
		 */
		final boolean shared;

		ItemView(String name, String kind, Engine.ItemMeta meta, Luck.ItemAnalysis a, Luck.Pool pool, boolean shared)
		{
			this.name = name;
			this.kind = kind;
			this.meta = meta;
			this.a = a;
			this.pool = pool;
			this.shared = shared;
		}
	}

	/**
	 * The "any unique" (or "unique table") card.
	 */
	static final class AnyView
	{
		final Luck.AnyUnique u;
		final List<String> names;
		final List<String> separate;
		/**
		 * A slot whose early hits give a non-unique item (the gold rings).
		 */
		final boolean gold;

		AnyView(Luck.AnyUnique u, List<String> names, List<String> separate, boolean gold)
		{
			this.u = u;
			this.names = Collections.unmodifiableList(new ArrayList<>(names));
			this.separate = Collections.unmodifiableList(new ArrayList<>(separate));
			this.gold = gold;
		}

		/**
		 * The boss rolls its other uniques separately, so this card is the unique table itself.
		 */
		boolean isTable()
		{
			return !separate.isEmpty();
		}
	}

	/**
	 * A boss page's tracker tab.
	 */
	static final class BossView
	{
		final String id;
		final long rev;
		final int kc;
		final int window;
		final List<ItemView> items;
		final AnyView any;
		final BossState state;
		/**
		 * Items on the drop table that are not shown yet, in name order.
		 */
		final List<String> addable;

		BossView(String id, long rev, int kc, int window, List<ItemView> items, AnyView any, BossState state, List<String> addable)
		{
			this.id = id;
			this.rev = rev;
			this.kc = kc;
			this.window = window;
			this.items = Collections.unmodifiableList(items);
			this.any = any;
			this.state = state;
			this.addable = Collections.unmodifiableList(addable);
		}
	}

	/**
	 * A line of the overview's rankings: one item, or one item pooled over the bosses that drop it.
	 */
	static final class Rank
	{
		/**
		 * The boss to open when the line is clicked.
		 */
		final String bossId;
		/**
		 * Where it drops: the boss, or "Duke Sucellus +3 more" when pooled.
		 */
		final String where;
		final String item;
		final int got;
		final double expected;
		final double pct;
		/**
		 * The chance, from all the player's kills, of having at least one more copy than they do by now.
		 */
		final double chance;
		final Luck.Label label;
		final boolean pooled;

		Rank(String bossId, String where, String item, int got, double expected, double pct, double chance, Luck.Label label, boolean pooled)
		{
			this.bossId = bossId;
			this.where = where;
			this.item = item;
			this.got = got;
			this.expected = expected;
			this.pct = pct;
			this.chance = chance;
			this.label = label;
			this.pooled = pooled;
		}
	}

	/**
	 * A line of the overview's list of the player's bosses.
	 */
	static final class BossRow
	{
		final String id;
		final String name;
		final int kc;
		final int got;
		final int total;
		final Luck.AnyUnique any;
		final long updated;

		BossRow(String id, String name, int kc, int got, int total, Luck.AnyUnique any, long updated)
		{
			this.id = id;
			this.name = name;
			this.kc = kc;
			this.got = got;
			this.total = total;
			this.any = any;
			this.updated = updated;
		}
	}

	static final class OverviewView
	{
		final long rev;
		final int tracked;
		final List<Rank> driest;
		final List<Rank> hottest;
		final List<BossRow> bosses;
		final List<Luck.Pool> pooled;

		OverviewView(long rev, int tracked, List<Rank> driest, List<Rank> hottest, List<BossRow> bosses, List<Luck.Pool> pooled)
		{
			this.rev = rev;
			this.tracked = tracked;
			this.driest = driest;
			this.hottest = hottest;
			this.bosses = bosses;
			this.pooled = pooled;
		}
	}

	static final class TableRow
	{
		final String name;
		final double p;
		final String qty;
		final List<String> printed;
		final List<String> where;
		final boolean log;
		final boolean notable;

		TableRow(String name, double p, String qty, List<String> printed, List<String> where, boolean log, boolean notable)
		{
			this.name = name;
			this.p = p;
			this.qty = qty;
			this.printed = printed;
			this.where = where;
			this.log = log;
			this.notable = notable;
		}
	}

	static final class TableView
	{
		final String bossId;
		final List<String> always;
		final List<TableRow> rows;

		TableView(String bossId, List<String> always, List<TableRow> rows)
		{
			this.bossId = bossId;
			this.always = always;
			this.rows = rows;
		}
	}

	/**
	 * The curve of "chance of getting it within this many kills of your last one".
	 */
	static final class ChartData
	{
		final String item;
		final double[] x;
		final double[] y;
		final double xmax;
		final int you;

		ChartData(String item, double[] x, double[] y, double xmax, int you)
		{
			this.item = item;
			this.x = x;
			this.y = y;
			this.xmax = xmax;
			this.you = you;
		}
	}

	/**
	 * How completely the wiki data for a boss was captured.
	 */
	static final class Confidence
	{
		final String level;
		final List<String> unknown;
		final List<String> inferred;
		final List<String> gaps;

		Confidence(String level, List<String> unknown, List<String> inferred, List<String> gaps)
		{
			this.level = level;
			this.unknown = unknown;
			this.inferred = inferred;
			this.gaps = gaps;
		}
	}

	private static final Pattern GAP = Pattern.compile("KNOWN GAP|NOT captured|not modelled|NOT modelled|CAVEAT|unconfirmed|not on page", Pattern.CASE_INSENSITIVE);

	static Confidence confidence(Boss b)
	{
		List<String> unknown = new ArrayList<>();
		List<String> inferred = new ArrayList<>();
		for (Row r : b.allRows())
		{
			if ("unknown".equals(r.rate))
			{
				unknown.add(r.name);
			}
			if (r.flagged("inferred"))
			{
				inferred.add(r.name);
			}
		}
		List<String> gaps = new ArrayList<>();
		for (String n : b.notes)
		{
			if (GAP.matcher(n).find())
			{
				gaps.add(n);
			}
		}
		String level = "high";
		if (!unknown.isEmpty() || !inferred.isEmpty() || !gaps.isEmpty())
		{
			level = "medium";
		}
		if (inferred.size() > 5 || unknown.size() > 1)
		{
			level = "medium-low";
		}
		return new Confidence(level, unknown, inferred, gaps);
	}

	/**
	 * Item name to everything the drop table says about it, for the "as printed on the wiki" column.
	 */
	static final class RowInfo
	{
		String qty = "";
		final List<String> rates = new ArrayList<>();
		final List<String> where = new ArrayList<>();
	}

	static Map<String, RowInfo> rowInfo(Boss b)
	{
		Map<String, RowInfo> info = new java.util.LinkedHashMap<>();
		for (com.dropluckledger.engine.Data.Table t : b.tables)
		{
			String label = t.label != null && !t.label.isEmpty() ? t.label : t.id;
			for (Row r : t.items)
			{
				addInfo(info, r, label);
			}
			for (com.dropluckledger.engine.Data.Sub s : t.subs)
			{
				for (Row r : s.items)
				{
					addInfo(info, r, label + " › " + s.id);
				}
			}
		}
		for (Row r : b.tert)
		{
			addInfo(info, r, "Tertiary");
		}
		for (Row r : b.pets)
		{
			addInfo(info, r, "Pet");
		}
		if (b.data != null)
		{
			for (com.dropluckledger.engine.Data.Shared s : b.data.shared)
			{
				for (com.dropluckledger.engine.Data.Table t : s.tables)
				{
					for (Row r : t.items)
					{
						if (!info.containsKey(r.name))
						{
							RowInfo i = new RowInfo();
							i.qty = r.qty == null ? "" : r.qty;
							info.put(r.name, i);
						}
					}
				}
			}
		}
		return info;
	}

	private static void addInfo(Map<String, RowInfo> info, Row r, String where)
	{
		RowInfo i = info.computeIfAbsent(r.name, k ->
		{
			RowInfo n = new RowInfo();
			n.qty = r.qty == null ? "" : r.qty;
			return n;
		});
		if (r.rate != null && !r.rate.isEmpty() && !i.rates.contains(r.rate) && i.rates.size() < 3)
		{
			i.rates.add(r.rate);
		}
		if (where != null && !where.isEmpty() && !i.where.contains(where))
		{
			i.where.add(where);
		}
	}
}
