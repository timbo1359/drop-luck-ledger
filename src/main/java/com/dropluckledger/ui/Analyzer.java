package com.dropluckledger.ui;

import com.dropluckledger.engine.Catalog;
import com.dropluckledger.engine.Data;
import com.dropluckledger.engine.Data.Boss;
import com.dropluckledger.engine.Env;
import com.dropluckledger.engine.Engine;
import com.dropluckledger.engine.Engine.ItemMeta;
import com.dropluckledger.engine.Luck;
import com.dropluckledger.ledger.BossState;
import com.dropluckledger.ledger.Drop;
import com.dropluckledger.ledger.Ledger;
import com.dropluckledger.ledger.Prefs;
import com.dropluckledger.plugin.ItemIcons;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Works out what the panel shows from the ledger.  The luck maths can take a moment, so this is meant to run on a
 * background thread; one call at a time (the maths keeps working state that must not be shared).  Results are kept
 * for as long as the part of the ledger they came from has not changed.
 */
final class Analyzer
{
	private static final Logger log = LoggerFactory.getLogger(Analyzer.class);

	private static final int MODELS = 24;
	private static final int ANALYSES = 1500;
	private static final int EXTRAS = 600;
	/**
	 * Stands for "worked out, and there is nothing to show".
	 */
	private static final Object NONE = new Object();
	/**
	 * The luck percentile from which an item counts as a spoon on the overview: "Slightly lucky" and up, the same line as the labels.
	 */
	private static final double SPOON_FROM = 0.60;
	/**
	 * Points on the chart.
	 */
	private static final int CHART_STEPS = 60;

	private final Ledger ledger;
	private final Data data;
	private final ItemIcons icons;
	private final Map<String, List<String>> logIndex;
	private final Map<String, Luck.Model> models = lru(MODELS);
	private final Map<String, Luck.ItemAnalysis> analyses = lru(ANALYSES);
	private final Map<String, Object> extras = lru(EXTRAS);

	Analyzer(Ledger ledger, ItemIcons icons)
	{
		this.ledger = ledger;
		this.data = ledger.data();
		this.icons = icons;
		this.logIndex = Catalog.logIndex(data);
	}

	private static <V> Map<String, V> lru(int max)
	{
		return new LinkedHashMap<String, V>(16, 0.75f, true)
		{
			private static final long serialVersionUID = 1L;

			@Override
			protected boolean removeEldestEntry(Map.Entry<String, V> eldest)
			{
				return size() > max;
			}
		};
	}

	/**
	 * Bosses whose collection log lists the item.
	 */
	List<String> bossesWith(String item)
	{
		List<String> l = logIndex.get(item);
		return l == null ? Collections.<String>emptyList() : l;
	}

	boolean shared(String item)
	{
		return bossesWith(item).size() > 1;
	}

	// ------------------------------------------------------------------ the maths, kept while nothing changes

	private Luck.Model model(String id, long rev)
	{
		String key = id + '|' + rev;
		Luck.Model m = models.get(key);
		if (m == null)
		{
			m = Luck.makeModel(data.boss(id), ledger.record(id), ledger.globals());
			models.put(key, m);
		}
		return m;
	}

	private Luck.ItemAnalysis analysis(String id, long rev, String name, int window)
	{
		String key = id + '|' + rev + '|' + name + '|' + window;
		Luck.ItemAnalysis a = analyses.get(key);
		if (a == null)
		{
			try
			{
				a = Luck.analyse(model(id, rev), name, window);
			}
			catch (RuntimeException ex)
			{
				log.warn("Could not work out {} at {}", name, id, ex);
				return null;
			}
			analyses.put(key, a);
		}
		return a;
	}

	private Views.AnyView anyUnique(String id, long rev, List<String> tracked, int window)
	{
		String key = "U|" + id + '|' + rev + '|' + window;
		Object c = extras.get(key);
		if (c != null)
		{
			return c == NONE ? null : (Views.AnyView) c;
		}
		Boss boss = data.boss(id);
		Catalog.AnyNames an = Catalog.anyNames(boss, tracked);
		Views.AnyView out = null;
		if (an.names.size() >= 2)
		{
			try
			{
				Luck.AnyUnique u = Luck.anyUnique(model(id, rev), an.names, window);
				if (u != null)
				{
					out = new Views.AnyView(u, an.names, an.sep, hasGold(boss, an.names));
				}
			}
			catch (RuntimeException ex)
			{
				log.warn("Could not work out the unique table of {}", id, ex);
			}
		}
		extras.put(key, out == null ? NONE : out);
		return out;
	}

	private static boolean hasGold(Boss boss, List<String> names)
	{
		Map<String, ItemMeta> m = boss.meta();
		for (String n : names)
		{
			ItemMeta im = m.get(n);
			if (im == null || im.seq == null)
			{
				continue;
			}
			for (ItemMeta other : m.values())
			{
				if (other.hidden && other.seq != null && other.seq.slot.equals(im.seq.slot))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * The figures for an item that several bosses drop, pooled over the bosses the player has kills at.  Null when fewer than
	 * two such bosses, or when the selected boss is not one of them.
	 */
	private Luck.Pool pool(String name, String selected, int window)
	{
		List<String> ids = new ArrayList<>();
		for (String i : bossesWith(name))
		{
			if (ledger.kc(i) > 0)
			{
				ids.add(i);
			}
		}
		if (ids.size() < 2 || !ids.contains(selected))
		{
			return null;
		}
		StringBuilder k = new StringBuilder("P|").append(name).append('|').append(window);
		Map<String, Long> revs = new LinkedHashMap<>();
		for (String i : ids)
		{
			long r = ledger.revision(i);
			revs.put(i, r);
			k.append('|').append(i).append(':').append(r);
		}
		String key = k.toString();
		Object c = extras.get(key);
		if (c != null)
		{
			return c == NONE ? null : (Luck.Pool) c;
		}
		// the most recent receipt (counted in kills) decides where each boss's streak starts
		Map<String, Integer> bestStart = null;
		int bestTotal = -1;
		for (String i : ids)
		{
			for (Drop d : ledger.copyOf(i).drops)
			{
				if (!name.equals(d.item) || d.kc == null)
				{
					continue;
				}
				Map<String, Integer> start = new LinkedHashMap<>();
				int total = 0;
				for (String j : ids)
				{
					int s = j.equals(i) ? d.kc : (d.snap != null && d.snap.get(j) != null ? d.snap.get(j) : 0);
					start.put(j, s);
					total += s;
				}
				if (bestStart == null || total > bestTotal)
				{
					bestStart = start;
					bestTotal = total;
				}
			}
		}
		List<Luck.PoolMember> members = new ArrayList<>();
		for (String i : ids)
		{
			int snap = bestStart == null ? 0 : Math.min(bestStart.getOrDefault(i, 0), ledger.kc(i));
			members.add(new Luck.PoolMember(model(i, revs.get(i)), snap));
		}
		Luck.Pool out = null;
		try
		{
			out = Luck.analysePool(members, name, window);
		}
		catch (RuntimeException ex)
		{
			log.warn("Could not pool {}", name, ex);
		}
		extras.put(key, out == null ? NONE : out);
		return out;
	}

	// ------------------------------------------------------------------ a boss page

	/**
	 * The tracker tab of a boss; null when cancelled.
	 */
	synchronized Views.BossView boss(String id, BooleanSupplier cancelled)
	{
		Boss boss = data.boss(id);
		if (boss == null)
		{
			return null;
		}
		final long rev = ledger.revision(id);
		final int window = ledger.prefs().window;
		final BossState st = ledger.copyOf(id);
		final List<String> names = ledger.trackedNames(id);
		final Map<String, ItemMeta> meta = boss.meta();
		List<Views.ItemView> items = new ArrayList<>();
		for (String n : names)
		{
			if (cancelled.getAsBoolean())
			{
				return null;
			}
			ItemMeta m = meta.get(n);
			Luck.ItemAnalysis a = analysis(id, rev, n, window);
			Luck.Pool p = a != null ? pool(n, id, window) : null;
			String kind = Catalog.kindOf(boss, n);
			items.add(new Views.ItemView(n, kind.equals("other") ? "drop" : kind, m, a, p, shared(n)));
			if (icons != null)
			{
				icons.id(n);
			}
		}
		Views.AnyView any = st.kc > 0 ? anyUnique(id, rev, names, window) : null;
		Collator collator = Collator.getInstance(Locale.ENGLISH);
		List<String> addable = new ArrayList<>();
		Set<String> shown = new HashSet<>(names);
		for (Map.Entry<String, ItemMeta> e : meta.entrySet())
		{
			if (!shown.contains(e.getKey()) && !e.getValue().hidden)
			{
				addable.add(e.getKey());
			}
		}
		addable.sort(collator);
		if (icons != null)
		{
			for (Drop d : st.drops)
			{
				icons.id(d.item);
			}
		}
		return new Views.BossView(id, rev, st.kc, window, items, any, st, addable);
	}

	// ------------------------------------------------------------------ the overview

	/**
	 * The overview page; null when cancelled.  It lists the bosses ticked under "Bosses to show" only; an item several bosses drop
	 * still has the luck of all the player's kills at them, so it reads the same as on the boss pages.
	 */
	synchronized Views.OverviewView overview(BooleanSupplier cancelled)
	{
		final Prefs prefs = ledger.prefs();
		final int window = prefs.window;
		final Set<String> hidden = new HashSet<>(prefs.hiddenBosses);
		final long overall = ledger.revision();
		List<String> active = ledger.activeBosses();
		List<String> ids = new ArrayList<>();
		for (String id : active)
		{
			if (!hidden.contains(id))
			{
				ids.add(id);
			}
		}
		List<Views.Rank> rows = new ArrayList<>();
		Set<String> ranked = new HashSet<>();
		List<Views.BossRow> bosses = new ArrayList<>();
		for (String id : ids)
		{
			if (cancelled.getAsBoolean())
			{
				return null;
			}
			Boss boss = data.boss(id);
			long rev = ledger.revision(id);
			BossState st = ledger.copyOf(id);
			List<String> names = ledger.trackedNames(id);
			if (st.kc > 0)
			{
				// the pieces of a set (the Barrows equipment, a moon's set) are one line: with many rare pieces one of them is bound to
				// come early, so the set's luck is all its pieces against all the pieces expected
				Map<String, SetTotal> sets = new LinkedHashMap<>();
				Map<String, ItemMeta> meta = boss.meta();
				for (String n : names)
				{
					Luck.ItemAnalysis a = analysis(id, rev, n, window);
					ItemMeta im = meta.get(n);
					if (a != null && im != null && im.group != null && a.expected != null && !shared(n))
					{
						sets.computeIfAbsent(im.group, g -> new SetTotal()).add(n, a.O, a.expected);
						continue;
					}
					if (a == null || a.collected || !(a.pNext > 0) || im != null && im.noSpoon)
					{
						continue;
					}
					// an item several bosses drop is one line, with the luck of all of them together
					Luck.Pool p = shared(n) ? pool(n, id, window) : null;
					if (p != null)
					{
						if (ranked.add(n) && !Double.isNaN(p.pct))
						{
							rows.add(rankOf(p, hidden));
						}
					}
					else if (a.pct != null && a.expected != null && a.another != null)
					{
						rows.add(new Views.Rank(id, boss.name, n, a.O, a.expected, a.pct, a.another, a.label, false));
					}
					if (icons != null)
					{
						icons.id(n);
					}
				}
				for (Map.Entry<String, SetTotal> e : sets.entrySet())
				{
					SetTotal t = e.getValue();
					if (t.expected > 0)
					{
						double pct = Luck.poissonPct(t.got, t.expected);
						rows.add(new Views.Rank(id, boss.name, e.getKey(), t.got, t.expected, pct, Luck.poissonAtLeast(t.got + 1, t.expected),
							Luck.luckLabel(pct), false, t.pieces, t.icon));
					}
				}
			}
			Set<String> have = new HashSet<>();
			for (Drop d : st.drops)
			{
				have.add(d.item);
			}
			int got = 0;
			Set<String> logNames = boss.logSet();
			for (String n : logNames)
			{
				if (have.contains(n))
				{
					got++;
				}
			}
			Views.AnyView any = st.kc > 0 ? anyUnique(id, rev, names, window) : null;
			bosses.add(new Views.BossRow(id, boss.name, st.kc, got, logNames.size(), any == null ? null : any.u, st.u));
		}
		List<Views.Rank> spoons = new ArrayList<>();
		for (Views.Rank r : rows)
		{
			if (r.got > 0 && r.pct >= SPOON_FROM)
			{
				spoons.add(r);
			}
		}
		// luckiest first; at the same luck the one further ahead of its rate
		spoons.sort(Comparator.comparingDouble((Views.Rank r) -> r.pct).reversed().thenComparingDouble(r -> r.expected - r.got)
			.thenComparing(r -> r.item, String.CASE_INSENSITIVE_ORDER));
		bosses.sort(Comparator.comparingLong((Views.BossRow b) -> b.updated).reversed().thenComparing(b -> b.name, String.CASE_INSENSITIVE_ORDER));
		List<Luck.Pool> pooled = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (String id : ids)
		{
			if (cancelled.getAsBoolean())
			{
				return null;
			}
			for (String n : ledger.trackedNames(id))
			{
				if (!shared(n) || !seen.add(n))
				{
					continue;
				}
				Luck.Pool p = pool(n, id, window);
				if (p != null)
				{
					pooled.add(p);
				}
			}
		}
		pooled.sort(Comparator.comparingDouble((Luck.Pool p) -> p.pct));
		return new Views.OverviewView(overall, ids.size(), active.size(), spoons, bosses, pooled);
	}

	/**
	 * The pieces of a set added up: the copies received, the copies expected, and the piece whose picture stands for the set (the
	 * first one received).
	 */
	private static final class SetTotal
	{
		int got;
		double expected;
		int pieces;
		String icon;

		void add(String item, int copies, double e)
		{
			if (icon == null || got == 0 && copies > 0)
			{
				icon = item;
			}
			got += copies;
			expected += e;
			pieces++;
		}
	}

	/**
	 * A ranking line for an item pooled over several bosses; it opens the shown boss the player has the most kills at.
	 */
	private static Views.Rank rankOf(Luck.Pool p, Set<String> hidden)
	{
		Luck.PoolPer main = null;
		for (Luck.PoolPer x : p.per)
		{
			if (!hidden.contains(x.id) && (main == null || x.K > main.K))
			{
				main = x;
			}
		}
		if (main == null)
		{
			main = p.per.get(0);
		}
		String where = main.name + (p.per.size() > 1 ? " +" + (p.per.size() - 1) + " more" : "");
		return new Views.Rank(main.id, where, p.name, p.O, p.expected, p.pct, p.another, p.label, true);
	}

	// ------------------------------------------------------------------ the drop table

	synchronized Views.TableView table(String id)
	{
		Boss boss = data.boss(id);
		if (boss == null)
		{
			return null;
		}
		Map<String, Object> vals = ledger.currentParams(id);
		Env env = Engine.makeEnv(boss, vals, ledger.globals(), Math.max(0, ledger.kc(id)));
		Map<String, Engine.ItemProb> probs = Engine.itemProbs(boss, env, null, null);
		Map<String, Views.RowInfo> info = Views.rowInfo(boss);
		Map<String, ItemMeta> meta = boss.meta();
		Set<String> logNames = boss.logSet();
		List<Views.TableRow> rows = new ArrayList<>();
		for (Map.Entry<String, Engine.ItemProb> e : probs.entrySet())
		{
			String n = e.getKey();
			Views.RowInfo ri = info.get(n);
			ItemMeta m = meta.get(n);
			if (m != null && m.hidden)
			{
				continue;
			}
			boolean notable = m != null && (m.isUnique() || m.isPet()) || logNames.contains(n);
			rows.add(new Views.TableRow(n, e.getValue().p, ri == null ? "" : ri.qty, ri == null ? new ArrayList<String>() : ri.rates,
				ri == null ? new ArrayList<String>() : ri.where, logNames.contains(n), notable));
			if (icons != null)
			{
				icons.id(n);
			}
		}
		// a tertiary or a pet whose rate is not published yet never rolls, but it is listed with the rest
		for (Map.Entry<String, ItemMeta> e : meta.entrySet())
		{
			String n = e.getKey();
			ItemMeta m = e.getValue();
			if (m.hidden || !m.rateUnknown() || probs.containsKey(n))
			{
				continue;
			}
			Views.RowInfo ri = info.get(n);
			rows.add(new Views.TableRow(n, 0, ri == null ? "" : ri.qty, ri == null ? new ArrayList<String>() : ri.rates,
				ri == null ? new ArrayList<String>() : ri.where, logNames.contains(n), m.isUnique() || m.isPet() || logNames.contains(n)));
		}
		rows.sort(Comparator.comparingDouble((Views.TableRow r) -> r.p).reversed());
		return new Views.TableView(id, boss.always, rows);
	}

	// ------------------------------------------------------------------ the chart

	/**
	 * The curve behind an item card: the chance of getting it within a number of kills after the last copy.
	 */
	synchronized Views.ChartData chart(String id, String item)
	{
		Boss boss = data.boss(id);
		if (boss == null)
		{
			return null;
		}
		final long rev = ledger.revision(id);
		final int window = ledger.prefs().window;
		Luck.ItemAnalysis a = analysis(id, rev, item, window);
		if (a == null)
		{
			return null;
		}
		Luck.Model m = model(id, rev);
		String nm = a.seq != null ? slotFirst(boss, a.seq.slot) : item;
		if (nm == null)
		{
			nm = item;
		}
		final int d = a.need;
		final int from = a.streakFrom;
		double xmax = Math.max(30, a.streak * 1.3);
		if (a.kto != null)
		{
			Double k99 = a.kto.get(0.99);
			if (k99 != null && !Double.isInfinite(k99) && !Double.isNaN(k99))
			{
				xmax = Math.max(xmax, a.streak + k99);
			}
		}
		xmax = Math.min(xmax, 3e6);
		double[] xs = new double[CHART_STEPS + 1];
		double[] ys = new double[CHART_STEPS + 1];
		for (int i = 0; i <= CHART_STEPS; i++)
		{
			int x = (int) Math.round(xmax * i / CHART_STEPS);
			xs[i] = x;
			ys[i] = x == 0 ? 0 : Luck.atLeast(d, m.runsFor(nm, from + 1, from + x));
		}
		return new Views.ChartData(item, xs, ys, xmax, a.streak);
	}

	private static String slotFirst(Boss boss, String slot)
	{
		for (Data.Table t : boss.tables)
		{
			for (Data.Row r : t.items)
			{
				if (r.seq != null && r.seq.slot.equals(slot))
				{
					return r.name;
				}
			}
		}
		return null;
	}
}
