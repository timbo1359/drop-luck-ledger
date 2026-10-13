package com.dropluckledger.history;

import com.dropluckledger.capture.BossIndex;
import com.dropluckledger.capture.BossIndex.KcLabel;
import com.dropluckledger.capture.Names;
import com.dropluckledger.engine.Catalog;
import com.dropluckledger.engine.Data;
import com.dropluckledger.engine.Data.Boss;
import com.dropluckledger.engine.Engine;
import com.dropluckledger.ledger.BossState;
import com.dropluckledger.ledger.Ledger;
import com.dropluckledger.ledger.Period;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out what the game's records add to the ledger, and applies it.
 * <p>
 * No game record says which kill an old drop came on, so history can only bring in kill counts and "owned copies"
 * (items with an unknown kill number).  Running it twice, or after the plugin has seen the same drops live, adds nothing
 * twice: copies already in the ledger are subtracted, and kill counts only ever go up.
 */
public final class HistoryPlanner
{
	/**
	 * Same cap as the web app: more copies than this is almost certainly a mistake in the source.  A boss with more kills than
	 * this allows as many copies as it has kills.
	 */
	public static final int MAX_COPIES = 200;

	/**
	 * A kill count to set.
	 */
	public static final class KcRow
	{
		public final String bossId;
		public final String name;
		public final int cur;
		public final int to;
		/**
		 * Kill-count label (as in the boss data) to the count the source had.
		 */
		public final Map<String, Integer> labels;
		public boolean on;

		KcRow(String bossId, String name, int cur, int to, Map<String, Integer> labels)
		{
			this.bossId = bossId;
			this.name = name;
			this.cur = cur;
			this.to = to;
			this.labels = labels;
			this.on = to > cur;
		}
	}

	/**
	 * Copies (drops) of one item to add.
	 */
	public static final class ItemRow
	{
		public final String bossId;
		public final String bossName;
		public final String item;
		/**
		 * How many drops the source stands for.
		 */
		public final int want;
		public final int have;
		public final int add;
		/**
		 * Copies an earlier import added with no kill number that the source does not support, because they counted every item of a
		 * stack as a drop.  Only set for rows worked out from a quantity.
		 */
		public final int remove;
		/**
		 * The most drops of this item the ledger takes for the boss.
		 */
		final int cap;
		/**
		 * Several bosses drop this item and the source did not say which: it went to the one with the most kills.
		 */
		public final boolean assumed;
		/**
		 * How many of the item the source said were received, when that is not the number of drops (a stackable item such as bolt
		 * racks arrives several at a time, so {@link #want} is worked out from this); 0 when {@link #want} is exactly what the
		 * source said.
		 */
		public final int quantity;
		public boolean on;

		ItemRow(String bossId, String bossName, String item, int want, int have, int imported, int cap, boolean assumed, int quantity)
		{
			this.bossId = bossId;
			this.bossName = bossName;
			this.item = item;
			this.want = want;
			this.have = have;
			this.cap = cap;
			this.add = Math.max(0, Math.min(want, cap) - have);
			this.remove = quantity > 0 ? Math.min(imported, Math.max(0, have - Math.min(want, cap))) : 0;
			this.assumed = assumed;
			this.quantity = quantity;
			this.on = add > 0 || remove > 0;
		}

		/**
		 * True when {@link #want} is an estimate from a received quantity rather than a count of drops.
		 */
		public boolean estimated()
		{
			return quantity > 0;
		}
	}

	public static final class Plan
	{
		public final List<KcRow> kc = new ArrayList<>();
		public final List<ItemRow> items = new ArrayList<>();
		/**
		 * Kill-count entries that are not a boss in the ledger ("name count").
		 */
		public final List<String> kcSkipped = new ArrayList<>();
		/**
		 * Item names that are not tracked.
		 */
		public final List<String> itemsMissing = new ArrayList<>();
		public int itemsTotal;
		public final Set<String> sources = new LinkedHashSet<>();

		public int kcToApply()
		{
			int n = 0;
			for (KcRow r : kc)
			{
				if (r.on)
				{
					n++;
				}
			}
			return n;
		}

		public int itemRowsToApply()
		{
			int n = 0;
			for (ItemRow r : items)
			{
				if (r.on && (r.add > 0 || r.remove > 0))
				{
					n++;
				}
			}
			return n;
		}

		public int copiesToAdd()
		{
			int n = 0;
			for (ItemRow r : items)
			{
				if (r.on)
				{
					n += r.add;
				}
			}
			return n;
		}

		/**
		 * Copies of an earlier import that the rows ticked would take back.
		 */
		public int copiesToRemove()
		{
			int n = 0;
			for (ItemRow r : items)
			{
				if (r.on)
				{
					n += r.remove;
				}
			}
			return n;
		}

		public boolean isEmpty()
		{
			return kcToApply() == 0 && copiesToAdd() == 0 && copiesToRemove() == 0;
		}
	}

	private final Ledger ledger;
	private final Data data;
	private final BossIndex index;
	private final Map<String, List<String>> logIndex;
	private final Map<String, String> itemKey = new HashMap<>();

	public HistoryPlanner(Ledger ledger, BossIndex index)
	{
		this.ledger = ledger;
		this.data = ledger.data();
		this.index = index;
		this.logIndex = Catalog.logIndex(data);
		for (String n : logIndex.keySet())
		{
			itemKey.putIfAbsent(Names.flat(n), n);
		}
		// collection log pages use the game's names, which differ from the wiki's for a few items
		for (Boss b : data.bosses)
		{
			for (Data.Row r : b.allRows())
			{
				String game = r.attr("game");
				if (r.log && game != null && !game.isEmpty())
				{
					itemKey.putIfAbsent(Names.flat(game), r.name);
				}
			}
		}
	}

	public Plan plan(HistoryInput in)
	{
		Plan plan = new Plan();
		plan.sources.addAll(in.sources);

		// ---- kill counts: one row per boss, summing its modes
		Map<String, Map<String, Integer>> byBoss = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> e : in.kc.entrySet())
		{
			KcLabel kl = index.kc(e.getKey());
			if (kl == null)
			{
				plan.kcSkipped.add(e.getKey() + " " + e.getValue());
				continue;
			}
			byBoss.computeIfAbsent(kl.bossId, k -> new LinkedHashMap<>()).merge(kl.label, e.getValue(), Math::max);
		}
		Map<String, Integer> kcTo = new HashMap<>();
		for (Map.Entry<String, Map<String, Integer>> e : byBoss.entrySet())
		{
			int sum = 0;
			for (int v : e.getValue().values())
			{
				sum += v;
			}
			kcTo.put(e.getKey(), sum);
			plan.kc.add(new KcRow(e.getKey(), data.boss(e.getKey()).name, ledger.kc(e.getKey()), sum, e.getValue()));
		}
		plan.kc.sort(Comparator.<KcRow>comparingInt(r -> -r.to).thenComparing(r -> r.name));

		// ---- owned copies
		Map<String, Integer> exact = new HashMap<>(); // bossId|item -> drops the source named a boss for
		Map<String, Integer> quantities = new HashMap<>(); // bossId|item -> the received quantity behind it, when that is not the drops
		Map<String, Integer> exactTotal = new HashMap<>(); // item -> the same over all bosses
		class Page
		{
			int n;
			final List<String> pages = new ArrayList<>();
		}
		Map<String, Page> perItem = new LinkedHashMap<>();
		LinkedHashSet<String> missing = new LinkedHashSet<>();
		// what the sources that name the boss received, summed over their records (one per name the boss's loot goes by)
		Map<String, Integer> exactQty = new LinkedHashMap<>();
		for (HistoryInput.Owned o : in.owned)
		{
			plan.itemsTotal++;
			if (o.bossId != null)
			{
				Boss b = data.boss(o.bossId);
				String item = b == null ? null : index.item(o.bossId, o.item);
				if (item == null || !Catalog.defaultTracked(b).contains(item))
				{
					missing.add(o.item);
					continue;
				}
				exactQty.merge(o.bossId + '|' + item, o.count, Integer::sum);
				continue;
			}
			String item = itemKey.get(Names.flat(o.item));
			if (item == null)
			{
				missing.add(o.item);
				continue;
			}
			Page p = perItem.computeIfAbsent(item, k -> new Page());
			p.n = Math.max(p.n, o.count); // a received quantity, not yet drops
			if (o.page != null)
			{
				p.pages.add(Names.norm(o.page));
			}
		}
		for (Map.Entry<String, Integer> e : exactQty.entrySet())
		{
			String key = e.getKey();
			Boss b = data.boss(key.substring(0, key.indexOf('|')));
			String item = key.substring(key.indexOf('|') + 1);
			int drops = Quantities.drops(b, item, e.getValue());
			exact.put(key, drops);
			if (drops != e.getValue())
			{
				quantities.put(key, e.getValue());
			}
		}
		for (Map.Entry<String, Integer> e : exact.entrySet())
		{
			exactTotal.merge(e.getKey().substring(e.getKey().indexOf('|') + 1), e.getValue(), Integer::sum);
		}
		Map<String, int[]> want = new LinkedHashMap<>(); // bossId|item -> {copies, assumed}
		for (Map.Entry<String, Integer> e : exact.entrySet())
		{
			want.put(e.getKey(), new int[]{e.getValue(), 0});
		}
		for (Map.Entry<String, Page> e : perItem.entrySet())
		{
			String item = e.getKey();
			Page p = e.getValue();
			List<String> cands = logIndex.get(item);
			String id;
			boolean assumed = false;
			if (cands.size() == 1)
			{
				id = cands.get(0);
			}
			else
			{
				List<String> byPage = new ArrayList<>();
				for (String c : cands)
				{
					String bn = Names.norm(data.boss(c).name);
					for (String pg : p.pages)
					{
						if (pg.contains(bn) && !byPage.contains(c))
						{
							byPage.add(c);
						}
					}
				}
				if (byPage.size() == 1)
				{
					id = byPage.get(0);
				}
				else
				{
					List<String> sorted = new ArrayList<>(cands);
					sorted.sort(Comparator.<String>comparingInt(c -> -(kcTo.containsKey(c) ? kcTo.get(c) : ledger.kc(c))));
					id = sorted.get(0);
					assumed = true;
				}
			}
			int drops = Quantities.drops(data.boss(id), item, p.n);
			int n = drops - exactTotal.getOrDefault(item, 0);
			if (n <= 0)
			{
				continue;
			}
			String key = id + '|' + item;
			if (drops != p.n)
			{
				quantities.merge(key, p.n, Integer::sum);
			}
			int[] cur = want.get(key);
			if (cur == null)
			{
				want.put(key, new int[]{n, assumed ? 1 : 0});
			}
			else
			{
				cur[0] += n;
				cur[1] = assumed ? 1 : cur[1];
			}
		}
		for (Map.Entry<String, int[]> e : want.entrySet())
		{
			String id = e.getKey().substring(0, e.getKey().indexOf('|'));
			String item = e.getKey().substring(e.getKey().indexOf('|') + 1);
			BossState st = ledger.copyOf(id);
			plan.items.add(new ItemRow(id, data.boss(id).name, item, e.getValue()[0], st.copies(item), st.importedUnknown(item), copyCap(id, kcTo),
				e.getValue()[1] != 0, quantities.getOrDefault(e.getKey(), 0)));
		}
		plan.items.sort(Comparator.<ItemRow, String>comparing(r -> r.bossName, String.CASE_INSENSITIVE_ORDER).thenComparing(r -> r.item));
		plan.itemsMissing.addAll(missing);
		return plan;
	}

	/**
	 * The most copies of an item the ledger takes for a boss: {@link #MAX_COPIES}, or the number of kills when there are more.
	 */
	private int copyCap(String bossId, Map<String, Integer> kcTo)
	{
		Integer planned = kcTo.get(bossId);
		return Math.max(MAX_COPIES, Math.max(planned == null ? 0 : planned, ledger.kc(bossId)));
	}

	/**
	 * Applies the rows that are switched on.
	 *
	 * @return {kill counts set, copies added, copies taken back}
	 */
	public int[] apply(Plan plan)
	{
		int kcSet = 0;
		int copies = 0;
		int removed = 0;
		for (KcRow r : plan.kc)
		{
			if (!r.on)
			{
				continue;
			}
			final Boss boss = data.boss(r.bossId);
			ledger.update(r.bossId, s ->
			{
				boolean fresh = s.kc == 0 && s.drops.isEmpty() && s.segs.size() == 1;
				if (fresh)
				{
					layOutModes(s, boss, r);
				}
				for (Map.Entry<String, Integer> e : r.labels.entrySet())
				{
					s.labels().merge(e.getKey(), e.getValue(), Math::max);
				}
				s.kc = Math.max(s.kc, Math.max(r.to, s.maxDropKc()));
				s.prov = null;
				s.provDrops = null;
				return null;
			});
			kcSet++;
		}
		for (ItemRow r : plan.items)
		{
			if (r.on && (r.add > 0 || r.remove > 0))
			{
				// re-check against the ledger as it is now, in case something changed since the plan was made
				BossState st = ledger.copyOf(r.bossId);
				int have = st.copies(r.item);
				int target = Math.min(r.want, r.cap);
				copies += ledger.addOwned(r.bossId, r.item, Math.max(0, target - have));
				if (r.remove > 0)
				{
					removed += ledger.removeOwned(r.bossId, r.item, Math.min(st.importedUnknown(r.item), Math.max(0, have - target)));
				}
			}
		}
		return new int[]{kcSet, copies, removed};
	}

	/**
	 * For a boss with no kills yet: the settings of its kill-count labels become periods.  With several labels (normal and
	 * challenge mode, awakened and normal ...) the kills of each label form one block of kills with that label's settings,
	 * in the order of the boss data.  The real order is unknown, but the totals per mode are right.
	 */
	private static void layOutModes(BossState s, Boss boss, KcRow r)
	{
		Map<String, Object> defaults = Engine.defaultParams(boss);
		if (r.labels.size() < 2)
		{
			Map<String, Object> patch = BossIndex.patchOf(boss, r.labels.keySet().iterator().next());
			if (!patch.isEmpty())
			{
				Ledger.applyParamsLocked(s, 1, patch, defaults);
			}
			return;
		}
		List<Period> periods = new ArrayList<>();
		int from = 1;
		for (String label : boss.kc)
		{
			Integer n = r.labels.get(label);
			if (n == null)
			{
				continue;
			}
			Map<String, Object> params = new LinkedHashMap<>(s.segs.get(0).params);
			params.putAll(BossIndex.patchOf(boss, label));
			if (periods.isEmpty() || !samePeriod(periods.get(periods.size() - 1).params, params, defaults))
			{
				periods.add(new Period(from, params));
			}
			from += n;
		}
		if (!periods.isEmpty())
		{
			s.segs = periods;
		}
	}

	private static boolean samePeriod(Map<String, Object> a, Map<String, Object> b, Map<String, Object> defaults)
	{
		Set<String> keys = new LinkedHashSet<>(a.keySet());
		keys.addAll(b.keySet());
		for (String k : keys)
		{
			Object x = a.containsKey(k) ? a.get(k) : defaults.get(k);
			Object y = b.containsKey(k) ? b.get(k) : defaults.get(k);
			if (x instanceof Number && y instanceof Number)
			{
				if (((Number) x).doubleValue() != ((Number) y).doubleValue())
				{
					return false;
				}
			}
			else if (!String.valueOf(x).equals(String.valueOf(y)))
			{
				return false;
			}
		}
		return true;
	}
}
