package com.dropluckledger.engine;

import com.dropluckledger.engine.Data.Boss;
import com.dropluckledger.engine.Data.Row;
import com.dropluckledger.engine.Data.Sub;
import com.dropluckledger.engine.Data.Table;
import com.dropluckledger.engine.Data.VarClause;
import com.dropluckledger.engine.Engine.ItemMeta;
import com.dropluckledger.engine.Engine.SeqInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The luck maths: how likely was your drop record, how long a dry streak you are on, what comes next.
 * Port of luck.js.  A "run list" is [(p, n)]: n consecutive kills that each had probability p of producing the item.
 */
public final class Luck
{
	private Luck()
	{
	}

	// ------------------------------------------------------------------ run lists and the Poisson-binomial count
	public static final class Run
	{
		public double p;
		public long n;

		public Run(double p, long n)
		{
			this.p = p;
			this.n = n;
		}
	}

	/**
	 * pmf[0..m] of Binomial(n, p), exact for j &lt;= m.
	 */
	static double[] binomPmf(double n, double p, int m)
	{
		double[] out = new double[m + 1];
		if (p <= 0)
		{
			out[0] = 1;
			return out;
		}
		if (p >= 1)
		{
			if (n <= m)
			{
				out[(int) n] = 1;
			}
			return out;
		}
		double lq = Math.log1p(-p);
		double lp = Math.log(p);
		double lg = n * lq;
		out[0] = Math.exp(lg);
		for (int j = 1; j <= m && j <= n; j++)
		{
			lg += Math.log((n - j + 1) / j) + lp - lq;
			out[j] = Math.exp(lg);
		}
		return out;
	}

	/**
	 * P(X = j) for j = 0..m, where X is the number of hits over the runs.
	 */
	public static double[] countDist(List<Run> runs, int m)
	{
		double[] dist = new double[m + 1];
		dist[0] = 1;
		for (Run r : runs)
		{
			if (r.n <= 0 || r.p <= 0)
			{
				continue;
			}
			double[] pm = binomPmf(r.n, r.p, m);
			double[] nd = new double[m + 1];
			for (int i = 0; i <= m; i++)
			{
				double a = dist[i];
				if (!Expr.truthy(a))
				{
					continue;
				}
				for (int j = 0; i + j <= m; j++)
				{
					nd[i + j] += a * pm[j];
				}
			}
			dist = nd;
		}
		return dist;
	}

	public static double logSurv(List<Run> runs)
	{
		double s = 0;
		for (Run r : runs)
		{
			if (r.p >= 1 && r.n > 0)
			{
				return Double.NEGATIVE_INFINITY;
			}
			if (r.n > 0 && r.p > 0)
			{
				s += r.n * Math.log1p(-r.p);
			}
		}
		return s;
	}

	/**
	 * P(X &gt;= need).
	 */
	public static double atLeast(int need, List<Run> runs)
	{
		if (need <= 0)
		{
			return 1;
		}
		if (need == 1)
		{
			return -Math.expm1(logSurv(runs));
		}
		if (need > 3000)
		{
			// normal approximation for very large counts
			double mu = 0;
			double v = 0;
			for (Run r : runs)
			{
				mu += r.n * r.p;
				v += r.n * r.p * (1 - r.p);
			}
			double z = (need - 0.5 - mu) / Math.sqrt(Math.max(v, 1e-12));
			return 0.5 * erfc(z / Math.sqrt(2));
		}
		double[] d = countDist(runs, need - 1);
		double s = 0;
		for (double x : d)
		{
			s += x;
		}
		return Math.max(0, 1 - s);
	}

	private static double erfc(double x)
	{
		// Abramowitz-Stegun 7.1.26 (enough for the rare large-count approximation)
		double s = x < 0 ? -1 : 1;
		x = Math.abs(x);
		double t = 1 / (1 + 0.3275911 * x);
		double y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-x * x);
		return s > 0 ? 1 - y : 1 + y;
	}

	// ------------------------------------------------------------------ a count taken as Poisson

	/**
	 * P(X = k) for X ~ Poisson(e).
	 */
	static double poissonPmf(int k, double e)
	{
		if (!(e > 0))
		{
			return k == 0 ? 1 : 0;
		}
		return Math.exp(-e + k * Math.log(e) - logFactorial(k));
	}

	private static double logFactorial(int k)
	{
		if (k < 2)
		{
			return 0;
		}
		if (k < 256)
		{
			double s = 0;
			for (int i = 2; i <= k; i++)
			{
				s += Math.log(i);
			}
			return s;
		}
		double x = k;
		return x * Math.log(x) - x + 0.5 * Math.log(2 * Math.PI * x) + 1 / (12 * x) - 1 / (360 * x * x * x);
	}

	/**
	 * P(X &gt;= m) for X ~ Poisson(e), worked out from the smaller side so that a tiny chance keeps its digits.
	 */
	public static double poissonAtLeast(int m, double e)
	{
		if (m <= 0)
		{
			return 1;
		}
		if (!(e > 0))
		{
			return 0;
		}
		if (m <= e)
		{
			double below = 0;
			for (int k = 0; k < m; k++)
			{
				below += poissonPmf(k, e);
			}
			return Math.max(0, 1 - below);
		}
		double above = 0;
		double t = poissonPmf(m, e);
		for (int k = m; t > above * 1e-17 && k < m + 100_000; k++)
		{
			above += t;
			t *= e / (k + 1);
		}
		return Math.min(1, above);
	}

	/**
	 * The luck percentile of m copies when e are expected, taken as Poisson: P(X &lt; m) + P(X = m) / 2.
	 */
	public static double poissonPct(int m, double e)
	{
		return Math.max(0, Math.min(1, 1 - poissonAtLeast(m, e) + 0.5 * poissonPmf(m, e)));
	}

	public static double sumP(List<Run> runs)
	{
		double s = 0;
		for (Run r : runs)
		{
			s += r.p * r.n;
		}
		return s;
	}

	public static long countKills(List<Run> runs)
	{
		long s = 0;
		for (Run r : runs)
		{
			s += r.n;
		}
		return s;
	}

	/**
	 * Concatenates run lists and compresses neighbours with the same chance.
	 */
	public static List<Run> mergeRuns(List<List<Run>> lists)
	{
		List<Run> out = new ArrayList<>();
		for (List<Run> l : lists)
		{
			for (Run r : l)
			{
				if (r.n <= 0)
				{
					continue;
				}
				Run last = out.isEmpty() ? null : out.get(out.size() - 1);
				if (last != null && Math.abs(last.p - r.p) < 1e-15)
				{
					last.n += r.n;
				}
				else
				{
					out.add(new Run(r.p, r.n));
				}
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ boss dependency scan
	/**
	 * What in a boss's data depends on the account: kill count ramps, owned copies, pity timers, follow tables.
	 */
	public static final class Dep
	{
		public boolean kcDep;
		public int cap = 1000;
		public final Set<String> ownedNames = new LinkedHashSet<>();
		public final Map<String, Integer> pity = new LinkedHashMap<>();
		public final List<String> follows = new ArrayList<>();
		public final Map<String, Set<String>> nodupGroups = new LinkedHashMap<>();
	}

	private static final Pattern NUM = Pattern.compile("(\\d+(?:\\.\\d+)?)");

	static Dep scanBoss(Boss boss)
	{
		Dep dep = new Dep();
		List<Double> lits = new ArrayList<>();
		class See
		{
			void expr(Expr fn, String rowName)
			{
				if (fn == null)
				{
					return;
				}
				Set<String> ids = fn.idents();
				if (ids.contains("kc"))
				{
					dep.kcDep = true;
					Matcher m = NUM.matcher(fn.src() == null ? "" : fn.src());
					while (m.find())
					{
						lits.add(Double.parseDouble(m.group(1)));
					}
				}
				if ((ids.contains("owned") || ids.contains("setowned")) && rowName != null)
				{
					dep.ownedNames.add(rowName);
				}
			}

			void row(Row r)
			{
				expr(r.expr, r.name);
				expr(r.ifFn, null);
				if (r.var != null)
				{
					for (VarClause v : r.var)
					{
						expr(v.rate, r.name);
					}
				}
				if (r.pity != 0 && !Double.isNaN(r.pity))
				{
					dep.pity.put(r.name, (int) r.pity);
				}
				String g = r.attrs.get("nodup");
				if (g != null && !g.isEmpty())
				{
					dep.nodupGroups.computeIfAbsent(g, k -> new LinkedHashSet<>()).add(r.name);
				}
			}
		}
		See see = new See();
		for (Table t : boss.tables)
		{
			see.expr(t.ifFn, null);
			see.expr(t.gateFn, null);
			see.expr(t.multFn, null);
			see.expr(t.rollsFn, null);
			String f = t.opts.get("follow");
			if (f != null && !f.isEmpty())
			{
				dep.follows.add(f);
			}
			if (t.petslot > 0)
			{
				// the follow table's rows change once the boss's pet is owned
				for (Row r : boss.pets)
				{
					dep.ownedNames.add(r.name);
				}
			}
			for (Row r : t.items)
			{
				see.row(r);
			}
			for (Sub sb : t.subs)
			{
				for (Row r : sb.items)
				{
					see.row(r);
				}
			}
		}
		for (Row r : boss.tert)
		{
			see.row(r);
		}
		for (Row r : boss.pets)
		{
			see.row(r);
		}
		for (Data.Derived d : boss.derived)
		{
			see.expr(d.f, null);
		}
		see.expr(boss.rollsFn, null);
		for (Set<String> set : dep.nodupGroups.values())
		{
			dep.ownedNames.addAll(set);
		}
		if (dep.kcDep)
		{
			double m = 0;
			for (double x : lits)
			{
				if (x >= 2 && x <= 5000)
				{
					m = Math.max(m, x);
				}
			}
			dep.cap = m != 0 ? (int) Math.min(2000, Math.ceil(m) + 2) : 1000;
		}
		return dep;
	}

	// ------------------------------------------------------------------ per-boss model
	/**
	 * One logged drop.  kc is the kill number it came on, or null when that is unknown.
	 */
	public static final class DropRec
	{
		public final String item;
		public final Integer kc;

		public DropRec(String item, Integer kc)
		{
			this.item = item;
			this.kc = kc;
		}
	}

	/**
	 * Boss settings that apply from a kill number on.
	 */
	public static final class Seg
	{
		public final int from;
		public final Map<String, Object> params;

		public Seg(int from, Map<String, Object> params)
		{
			this.from = from;
			this.params = params;
		}
	}

	/**
	 * The state of one boss the maths needs.
	 */
	public static final class BossRecord
	{
		public int kc;
		public List<Seg> segs = new ArrayList<>();
		public List<DropRec> drops = new ArrayList<>();
	}

	static final class Held
	{
		/**
		 * The kill numbers of the copies that have one, in order.
		 */
		final List<Integer> ks = new ArrayList<>();
		/**
		 * Those, and the kills given to the copies with no kill number for the chances (see the model), in order.
		 */
		final List<Integer> all = new ArrayList<>();
		int unk;
		/**
		 * Copies with no kill number that were given a kill.
		 */
		int placed;
		int total;
	}

	private static int lowerBound(List<Integer> a, int x)
	{
		int lo = 0;
		int hi = a.size();
		while (lo < hi)
		{
			int m = (lo + hi) >>> 1;
			if (a.get(m) < x)
			{
				lo = m + 1;
			}
			else
			{
				hi = m;
			}
		}
		return lo;
	}

	/**
	 * The chances of one boss for one account state.  Not thread safe (it memoises); build one per computation.
	 */
	public static final class Model
	{
		public final Boss boss;
		public final int K;
		final Dep dep;
		final List<Seg> segs;
		final List<Map<String, Object>> segEnv = new ArrayList<>();
		/**
		 * A key for each period's settings: periods with the same settings share their chances (a boss whose setting the game reveals
		 * for every kill can have hundreds of periods but only a few different settings).
		 */
		final List<String> segKey = new ArrayList<>();
		private final int[] segFrom;
		final Map<String, Held> byName = new HashMap<>();
		final Map<String, Double> globals;
		private final Map<String, Double> memo = new HashMap<>();

		Model(Boss boss, BossRecord st, Map<String, Double> globals)
		{
			this.boss = boss;
			this.dep = boss.dep();
			this.globals = globals == null ? new HashMap<>() : globals;
			this.K = Math.max(0, st.kc);
			List<Seg> ss = new ArrayList<>(st.segs);
			if (ss.isEmpty())
			{
				ss.add(new Seg(1, Engine.defaultParams(boss)));
			}
			ss.sort((a, b) -> Integer.compare(a.from, b.from));
			this.segs = ss;
			segFrom = new int[ss.size()];
			Map<String, String> keys = new HashMap<>();
			for (int i = 0; i < ss.size(); i++)
			{
				Seg s = ss.get(i);
				segFrom[i] = s.from;
				Map<String, Object> m = Engine.defaultParams(boss);
				if (s.params != null)
				{
					m.putAll(s.params);
				}
				segEnv.add(m);
				String sig = new java.util.TreeMap<>(m).toString();
				String key = keys.get(sig);
				if (key == null)
				{
					key = "s" + keys.size();
					keys.put(sig, key);
				}
				segKey.add(key);
			}
			for (DropRec d : st.drops)
			{
				Held o = byName.computeIfAbsent(d.item, k -> new Held());
				o.total++;
				if (d.kc == null)
				{
					o.unk++;
				}
				else
				{
					o.ks.add(d.kc);
				}
			}
			for (Held h : byName.values())
			{
				Collections.sort(h.ks);
				h.all.addAll(h.ks);
			}
			placeUnknown();
		}

		/**
		 * Copies with no kill number of the items whose copies change a chance (their own next one's, as for the Tombs of Amascut
		 * thread, or another item's): when they came is not known, yet the chance of every kill depends on it.  Each is given the kill
		 * at which the copies expected by then (the chances as they were, with the copies given kills so far) first reach the copies
		 * held plus a half: the usual history for that many copies.  One the expected copies never reach is given the last kill.  The
		 * kills given are only for the chances: the item's known kills, its dry streak and its count stay as they were.  (A pity row's
		 * kills since the last copy are not placed this way; no boss uses one.)  Same steps as the web app, for the same figures.
		 */
		private void placeUnknown()
		{
			List<String> toPlace = new ArrayList<>();
			for (Map.Entry<String, Held> e : byName.entrySet())
			{
				if (e.getValue().unk > 0 && dep.ownedNames.contains(e.getKey()))
				{
					toPlace.add(e.getKey());
				}
			}
			if (toPlace.isEmpty())
			{
				return;
			}
			Collections.sort(toPlace);
			double[] expected = new double[toPlace.size()];
			int[] knownSoFar = new int[toPlace.size()];
			for (int k = 1; k <= K; k++)
			{
				int left = 0;
				for (int i = 0; i < toPlace.size(); i++)
				{
					Held o = byName.get(toPlace.get(i));
					if (o.placed >= o.unk)
					{
						continue;
					}
					left++;
					expected[i] += pAt(toPlace.get(i), k);
					while (knownSoFar[i] < o.ks.size() && o.ks.get(knownSoFar[i]) <= k)
					{
						knownSoFar[i]++;
					}
					while (o.placed < o.unk && expected[i] >= knownSoFar[i] + o.placed + 0.5)
					{
						place(o, k);
					}
				}
				if (left == 0)
				{
					break;
				}
			}
			for (String n : toPlace)
			{
				Held o = byName.get(n);
				while (o.placed < o.unk)
				{
					place(o, K);
				}
			}
		}

		private static void place(Held o, int k)
		{
			o.all.add(lowerBound(o.all, k + 1), k);
			o.placed++;
		}

		private int segIdx(int k)
		{
			// the last period starting at or before kill k (the first period when none does)
			int lo = 0;
			int hi = segFrom.length - 1;
			int i = 0;
			while (lo <= hi)
			{
				int mid = (lo + hi) >>> 1;
				if (segFrom[mid] <= k)
				{
					i = mid;
					lo = mid + 1;
				}
				else
				{
					hi = mid - 1;
				}
			}
			return i;
		}

		int ownedAt(String name, int k)
		{
			Held o = byName.get(name);
			if (o == null)
			{
				return 0;
			}
			return lowerBound(o.all, k) + (k > K ? o.unk - o.placed : 0);
		}

		int lastBefore(String name, int k)
		{
			Held o = byName.get(name);
			if (o == null)
			{
				return 0;
			}
			int i = lowerBound(o.all, k);
			return i > 0 ? o.all.get(i - 1) : 0;
		}

		private Set<String> names1(String name)
		{
			Set<String> s = new LinkedHashSet<>(dep.follows);
			s.add(name);
			return s;
		}

		/**
		 * Chance per kill number k of receiving the item (names.size()==1 and !multi) or at least one of the items (multi).
		 */
		private double pAtImpl(List<String> names, boolean multi, final int k, final Integer ownedOverride)
		{
			int si = segIdx(k);
			int kcE = dep.kcDep ? Math.min(k - 1, dep.cap) : 0;
			StringBuilder sig = new StringBuilder();
			if (ownedOverride == null)
			{
				if (!dep.ownedNames.isEmpty())
				{
					boolean first = true;
					for (String n : dep.ownedNames)
					{
						if (!first)
						{
							sig.append(',');
						}
						first = false;
						sig.append(ownedAt(n, k));
					}
				}
			}
			else
			{
				sig.append("ov").append(ownedOverride);
			}
			StringBuilder since = new StringBuilder();
			if (!dep.pity.isEmpty())
			{
				boolean first = true;
				for (Map.Entry<String, Integer> e : dep.pity.entrySet())
				{
					if (!first)
					{
						since.append(',');
					}
					first = false;
					since.append(Math.min(e.getValue(), k - 1 - lastBefore(e.getKey(), k)));
				}
			}
			StringBuilder key = new StringBuilder();
			if (multi)
			{
				key.append("M:").append(String.join("\u0001", names));
			}
			else
			{
				key.append(names.get(0));
			}
			key.append('|').append(segKey.get(si)).append('|').append(kcE).append('|').append(sig).append('|').append(since);
			String ks = key.toString();
			Double hit = memo.get(ks);
			if (hit != null)
			{
				return hit;
			}
			Env env = Engine.makeEnv(boss, segEnv.get(si), globals, kcE);
			Engine.Ctx ctx = new Engine.Ctx()
			{
				@Override
				public int owned(Row row)
				{
					return ownedOverride != null ? ownedOverride : ownedAt(row.name, k);
				}

				@Override
				public double since(Row row)
				{
					return (k - 1) - lastBefore(row.name, k);
				}
			};
			double v;
			if (multi)
			{
				v = Engine.anyProb(boss, env, ctx, new LinkedHashSet<>(names), dep.follows);
			}
			else
			{
				Map<String, Engine.ItemProb> out = Engine.itemProbs(boss, env, ctx, names1(names.get(0)));
				Engine.ItemProb ip = out.get(names.get(0));
				v = ip != null ? ip.p : 0;
			}
			memo.put(ks, v);
			return v;
		}

		public double pAt(String name, int k)
		{
			return pAtImpl(Collections.singletonList(name), false, k, null);
		}

		/**
		 * As pAt but pretending the account owns exactly ownedOverride copies.
		 */
		public double pAt(String name, int k, int ownedOverride)
		{
			return pAtImpl(Collections.singletonList(name), false, k, ownedOverride);
		}

		public double pAtAny(List<String> names, int k)
		{
			return pAtImpl(names, true, k, null);
		}

		private final Map<String, List<String>> relatedCache = new HashMap<>();

		/**
		 * The items whose copies can change the chances of a no-duplicate group: the owned-dependent items of the tables the group is in
		 * (and of the pre-roll tables, when the group is in a base table).
		 */
		List<String> relatedOf(List<String> group)
		{
			String gk = String.join("\u0001", group);
			List<String> rel = relatedCache.get(gk);
			if (rel != null)
			{
				return rel;
			}
			Set<String> gs = new LinkedHashSet<>(group);
			Set<String> out = new LinkedHashSet<>(group);
			boolean inBase = false;
			for (Table t : boss.tables)
			{
				List<Row> rows = tableRows(t);
				boolean has = false;
				for (Row r : rows)
				{
					has |= gs.contains(r.name);
				}
				if (!has)
				{
					continue;
				}
				inBase |= !t.pre && !t.par;
				for (Row r : rows)
				{
					if (dep.ownedNames.contains(r.name))
					{
						out.add(r.name);
					}
				}
				if (t.petslot > 0)
				{
					// the table changes once the pet is owned
					for (Row r : boss.pets)
					{
						out.add(r.name);
					}
				}
			}
			if (inBase)
			{
				for (Table t : boss.tables)
				{
					if (t.pre)
					{
						for (Row r : tableRows(t))
						{
							if (dep.ownedNames.contains(r.name))
							{
								out.add(r.name);
							}
						}
					}
				}
			}
			rel = new ArrayList<>(out);
			relatedCache.put(gk, rel);
			return rel;
		}

		private static List<Row> tableRows(Table t)
		{
			List<Row> rows = new ArrayList<>(t.items);
			for (Sub sb : t.subs)
			{
				rows.addAll(sb.items);
			}
			return rows;
		}

		private String sinceSig(int k)
		{
			StringBuilder since = new StringBuilder();
			boolean first = true;
			for (Map.Entry<String, Integer> e : dep.pity.entrySet())
			{
				if (!first)
				{
					since.append(',');
				}
				first = false;
				since.append(Math.min(e.getValue(), k - 1 - lastBefore(e.getKey(), k)));
			}
			return since.toString();
		}

		/**
		 * What, besides the group's own copies, the group's chances at kill k depend on (equal keys: equal chances in every state).
		 */
		String groupCtxKey(List<String> group, int k)
		{
			StringBuilder b = new StringBuilder();
			b.append(segKey.get(segIdx(k))).append('|').append(dep.kcDep ? Math.min(k - 1, dep.cap) : 0).append('|');
			for (String n : relatedOf(group))
			{
				if (!group.contains(n))
				{
					b.append(ownedAt(n, k)).append(',');
				}
			}
			return b.append('|').append(sinceSig(k)).toString();
		}

		/**
		 * The chances of all items of a no-duplicate group at kill k, with the given copy counts for them (the other items as owned at
		 * kill k): the chances in a state of the group other than the one the account is in.
		 */
		double[] pAtGroup(List<String> group, int k, Map<String, Integer> counts)
		{
			int si = segIdx(k);
			int kcE = dep.kcDep ? Math.min(k - 1, dep.cap) : 0;
			StringBuilder key = new StringBuilder("G|").append(String.join("\u0001", group)).append('|').append(segKey.get(si)).append('|').append(kcE).append('|');
			for (String n : relatedOf(group))
			{
				Integer c = counts.get(n);
				key.append(c != null ? c : ownedAt(n, k)).append(',');
			}
			key.append('|').append(sinceSig(k));
			String ks = key.toString();
			double[] hit = groupMemo.get(ks);
			if (hit != null)
			{
				return hit;
			}
			Env env = Engine.makeEnv(boss, segEnv.get(si), globals, kcE);
			Engine.Ctx ctx = new Engine.Ctx()
			{
				@Override
				public int owned(Row row)
				{
					Integer c = counts.get(row.name);
					return c != null ? c : ownedAt(row.name, k);
				}

				@Override
				public double since(Row row)
				{
					return (k - 1) - lastBefore(row.name, k);
				}
			};
			Set<String> want = new LinkedHashSet<>(dep.follows);
			want.addAll(group);
			Map<String, Engine.ItemProb> out = Engine.itemProbs(boss, env, ctx, want);
			double[] v = new double[group.size()];
			for (int i = 0; i < v.length; i++)
			{
				Engine.ItemProb ip = out.get(group.get(i));
				v[i] = ip != null ? ip.p : 0;
			}
			groupMemo.put(ks, v);
			return v;
		}

		private final Map<String, double[]> groupMemo = new HashMap<>();

		public double pNext(String name)
		{
			return pAt(name, K + 1);
		}

		public int copies(String name)
		{
			Held o = byName.get(name);
			return o != null ? o.total : 0;
		}

		public List<Integer> known(String name)
		{
			Held o = byName.get(name);
			return o != null ? o.ks : Collections.<Integer>emptyList();
		}

		public int unknown(String name)
		{
			Held o = byName.get(name);
			return o != null ? o.unk : 0;
		}

		public List<Run> runsFor(String name, int from, int to)
		{
			return runsImpl(Collections.singletonList(name), false, from, to);
		}

		public List<Run> runsForAny(List<String> names, int from, int to)
		{
			return runsImpl(names, true, from, to);
		}

		/**
		 * Run list for kills from..to (inclusive, 1-indexed).
		 */
		private List<Run> runsImpl(List<String> names, boolean multi, int from, int to)
		{
			List<Run> out = new ArrayList<>();
			if (to < from)
			{
				return out;
			}
			TreeSet<Integer> pts = new TreeSet<>();
			pts.add(from);
			for (Seg s : segs)
			{
				if (s.from > from && s.from <= to)
				{
					pts.add(s.from);
				}
			}
			if (dep.kcDep)
			{
				for (int k = from + 1; k <= Math.min(to, dep.cap + 2); k++)
				{
					pts.add(k);
				}
			}
			if (!dep.ownedNames.isEmpty())
			{
				for (String n : dep.ownedNames)
				{
					Held o = byName.get(n);
					if (o != null)
					{
						for (int d : o.all)
						{
							if (d + 1 > from && d + 1 <= to)
							{
								pts.add(d + 1);
							}
						}
					}
				}
				if (K + 1 > from && K + 1 <= to)
				{
					pts.add(K + 1);
				}
			}
			for (String nm : names)
			{
				Integer pv = dep.pity.get(nm);
				if (pv == null)
				{
					continue;
				}
				Held o = byName.get(nm);
				List<Integer> ds = new ArrayList<>();
				ds.add(0);
				if (o != null)
				{
					ds.addAll(o.all);
				}
				for (int d : ds)
				{
					int[] xs = {d + 1, d + pv};
					for (int x : xs)
					{
						if (x > from && x <= to)
						{
							pts.add(x);
						}
					}
				}
			}
			Integer[] arr = pts.toArray(new Integer[0]);
			for (int i = 0; i < arr.length; i++)
			{
				int s = arr[i];
				int e = i + 1 < arr.length ? arr[i + 1] - 1 : to;
				double p = pAtImpl(names, multi, s, null);
				Run last = out.isEmpty() ? null : out.get(out.size() - 1);
				if (last != null && Math.abs(last.p - p) < 1e-15)
				{
					last.n += e - s + 1;
				}
				else
				{
					out.add(new Run(p, e - s + 1));
				}
			}
			return out;
		}
	}

	public static Model makeModel(Boss boss, BossRecord st, Map<String, Double> globals)
	{
		return new Model(boss, st, globals);
	}

	// ------------------------------------------------------------------ item analysis
	static List<Row> slotMembers(Boss boss, String slot)
	{
		List<Row> out = new ArrayList<>();
		for (Table t : boss.tables)
		{
			for (Row r : t.items)
			{
				if (r.seq != null && r.seq.slot.equals(slot))
				{
					out.add(r);
				}
			}
		}
		return out;
	}

	/**
	 * How many times the slot has hit.  Normally the sum of the copies of its members.  A member received on hit n of L implies all the
	 * earlier hits happened (a vestige is the 3rd vestige roll even if the two gold rings were never logged), so never infer fewer than
	 * L x (copies of the last member).
	 */
	static int slotHits(Model m, Boss boss, String slot)
	{
		List<Row> mem = slotMembers(boss, slot);
		if (mem.isEmpty())
		{
			return 0;
		}
		int len = 0;
		for (Row r : mem)
		{
			len = Math.max(len, r.seq.last);
		}
		int sum = 0;
		int fin = 0;
		for (Row r : mem)
		{
			int c = m.copies(r.name);
			sum += c;
			if (r.seq.last == len)
			{
				fin += c;
			}
		}
		return Math.max(sum, len * fin);
	}

	public static final class Label
	{
		public final String key;
		public final String text;

		Label(String key, String text)
		{
			this.key = key;
			this.text = text;
		}
	}

	public static Label luckLabel(Double pct)
	{
		if (pct == null || Double.isNaN(pct))
		{
			return new Label("na", "—");
		}
		if (pct < 0.05)
		{
			return new Label("vdry", "Very dry");
		}
		if (pct < 0.20)
		{
			return new Label("dry", "Dry");
		}
		if (pct < 0.40)
		{
			return new Label("below", "Slightly dry");
		}
		if (pct < 0.60)
		{
			return new Label("avg", "On rate");
		}
		if (pct < 0.80)
		{
			return new Label("above", "Slightly lucky");
		}
		if (pct < 0.95)
		{
			return new Label("lucky", "Lucky");
		}
		return new Label("vlucky", "Very lucky");
	}

	/**
	 * Kills needed from now (memoryless) for the chance of at least d hits to reach c; Infinity if unreachable.
	 */
	public static double killsTo(Model m, String name, int d, double c, int startK)
	{
		java.util.function.IntToDoubleFunction test = n -> atLeast(d, m.runsFor(name, startK + 1, startK + n));
		if (m.pAt(name, startK + 1) <= 0 && test.applyAsDouble(1) <= 0)
		{
			return Double.POSITIVE_INFINITY;
		}
		int hi = 1;
		while (test.applyAsDouble(hi) < c)
		{
			hi *= 2;
			if (hi > 20_000_000)
			{
				return Double.POSITIVE_INFINITY;
			}
		}
		int lo = hi / 2; // test(lo) < c (or lo = 0)
		while (hi - lo > 1)
		{
			int mid = (lo + hi) >> 1;
			if (test.applyAsDouble(mid) >= c)
			{
				hi = mid;
			}
			else
			{
				lo = mid;
			}
		}
		return hi;
	}

	/**
	 * The no-duplicate group an item belongs to (its names), or null.
	 */
	static List<String> nodupGroupOf(Model m, String name)
	{
		for (Set<String> set : m.dep.nodupGroups.values())
		{
			if (set.contains(name) && set.size() > 1)
			{
				return new ArrayList<>(set);
			}
		}
		return null;
	}

	/**
	 * True when the item's no-duplicate group is a "once" group: no protection once every item of it has been received.
	 */
	static boolean nodupOnce(Model m, String name)
	{
		for (Map.Entry<String, Set<String>> e : m.dep.nodupGroups.entrySet())
		{
			if (e.getValue().contains(name))
			{
				return Engine.onceGroup(e.getKey());
			}
		}
		return false;
	}

	/**
	 * The chances of all items of a "once" group after the set is complete: every item rolls at its own chance.
	 */
	private static double[] doneProbs(Model m, List<String> group, int[] counts, int k)
	{
		Map<String, Integer> c = new HashMap<>();
		for (int i = 0; i < group.size(); i++)
		{
			c.put(group.get(i), Math.max(1, counts == null ? 1 : counts[i]));
		}
		return m.pAtGroup(group, k, c);
	}

	/**
	 * The state that follows when item j of a no-duplicate group drops: it leaves the items at the fewest copies, and when none are left
	 * all of them are again (or, for a "once" group, the set is complete).
	 */
	private static int nextState(int s, int j, int full, int done, boolean once)
	{
		if (s == done)
		{
			return done;
		}
		int s2 = s & ~(1 << j);
		return s2 != 0 ? s2 : once ? done : full;
	}

	static final int NODUP_LIMIT = 200_000;

	/**
	 * The chances of a no-duplicate group's items in one state of the group (the bit mask of the items at the fewest copies), with
	 * the items outside the state at nothing.
	 */
	private static double[] stateProbs(Model m, List<String> group, int base, int state, int k)
	{
		Map<String, Integer> counts = new HashMap<>();
		for (int i = 0; i < group.size(); i++)
		{
			counts.put(group.get(i), base + (((state >> i) & 1) != 0 ? 0 : 1));
		}
		double[] all = m.pAtGroup(group, k, counts);
		double[] p = new double[all.length];
		for (int i = 0; i < p.length; i++)
		{
			p[i] = ((state >> i) & 1) != 0 ? all[i] : 0;
		}
		return p;
	}

	/**
	 * Chances of the next copy of an item of a no-duplicate group within the next n kills, and the kills to reach a chance: only the
	 * copies held the fewest of can drop, so an item that cannot drop now can once the others have.  The group's state (which items
	 * are at the fewest copies) is followed kill by kill.
	 */
	static void nodupForward(Model m, String x, List<String> group, int K, int[] windows, double[] cs, ItemAnalysis out, boolean once)
	{
		int n = group.size();
		int xi = group.indexOf(x);
		int full = (1 << n) - 1;
		// a "once" group whose set is complete: no item is held back any more
		final int done = full + 1;
		int mn = Integer.MAX_VALUE;
		int[] c0 = new int[n];
		for (int i = 0; i < n; i++)
		{
			c0[i] = m.ownedAt(group.get(i), K + 1);
			mn = Math.min(mn, c0[i]);
		}
		int s0 = 0;
		for (int i = 0; i < n; i++)
		{
			if (c0[i] == mn)
			{
				s0 |= 1 << i;
			}
		}
		if (once && mn >= 1)
		{
			s0 = done;
		}
		Map<Integer, Double> dist = new HashMap<>();
		dist.put(s0, 1.0);
		double absorbed = 0;
		int maxW = 0;
		for (int w : windows)
		{
			maxW = Math.max(maxW, w);
		}
		double[] want = cs.clone();
		Arrays.sort(want);
		int wi = 0;
		Map<Integer, Double> fwd = new HashMap<>();
		Map<Double, Double> kto = new LinkedHashMap<>();
		String lastCtx = null;
		Map<Integer, double[]> byS = new HashMap<>();
		int step = 0;
		while (step < NODUP_LIMIT && (step < maxW || wi < want.length) && !dist.isEmpty())
		{
			step++;
			int k = K + step;
			String ck = m.groupCtxKey(group, k);
			if (!ck.equals(lastCtx))
			{
				lastCtx = ck;
				byS = new HashMap<>();
			}
			Map<Integer, Double> nd = new HashMap<>();
			for (Map.Entry<Integer, Double> e : dist.entrySet())
			{
				int s = e.getKey();
				double q = e.getValue();
				double[] p = byS.get(s);
				if (p == null)
				{
					p = s == done ? doneProbs(m, group, c0, k) : stateProbs(m, group, mn, s, k);
					byS.put(s, p);
				}
				double tot = 0;
				for (double v : p)
				{
					tot += v;
				}
				absorbed += q * p[xi];
				if (tot < 1)
				{
					nd.merge(s, q * (1 - tot), Double::sum);
				}
				for (int j = 0; j < n; j++)
				{
					if (j == xi || !(p[j] > 0))
					{
						continue;
					}
					nd.merge(nextState(s, j, full, done, once), q * p[j], Double::sum);
				}
			}
			dist = nd;
			for (int w : windows)
			{
				if (w == step)
				{
					fwd.put(w, absorbed);
				}
			}
			while (wi < want.length && absorbed >= want[wi])
			{
				kto.put(want[wi], (double) step);
				wi++;
			}
		}
		for (int w : windows)
		{
			if (!out.fwd.containsKey(w))
			{
				out.fwd.put(w, fwd.containsKey(w) ? fwd.get(w) : absorbed);
			}
		}
		out.kto = new LinkedHashMap<>();
		for (double c : cs)
		{
			Double v = kto.get(c);
			out.kto.put(c, v != null ? v : Double.POSITIVE_INFINITY);
		}
	}

	/**
	 * The chances of the copies of an item of a no-duplicate group over kills 1..K, before knowing what dropped: which items can drop
	 * depends on what dropped before, so the chances as they were (nothing for an item between its copy and the rest of the set) would
	 * make every such item look lucky.  The group's state is followed kill by kill instead.
	 *
	 * @return P(at least n copies) for n = 0..cap, then the expected copies
	 */
	static double[] nodupCareer(Model m, String x, List<String> group, int K, int cap, boolean once)
	{
		int n = group.size();
		int xi = group.indexOf(x);
		int full = (1 << n) - 1;
		// a "once" group whose set is complete: no item is held back any more
		final int done = full + 1;
		int w = cap + 1;
		Map<Integer, Double> dist = new HashMap<>();
		dist.put(full * w, 1.0);
		double expected = 0;
		String lastCtx = null;
		Map<Integer, double[]> byS = new HashMap<>();
		for (int k = 1; k <= K; k++)
		{
			String ck = m.groupCtxKey(group, k);
			if (!ck.equals(lastCtx))
			{
				lastCtx = ck;
				byS = new HashMap<>();
			}
			Map<Integer, Double> nd = new HashMap<>();
			for (Map.Entry<Integer, Double> e : dist.entrySet())
			{
				int s = e.getKey() / w;
				int c = e.getKey() % w;
				double q = e.getValue();
				double[] p = byS.get(s);
				if (p == null)
				{
					p = s == done ? doneProbs(m, group, null, k) : stateProbs(m, group, 0, s, k);
					byS.put(s, p);
				}
				double tot = 0;
				for (double v : p)
				{
					tot += v;
				}
				expected += q * p[xi];
				if (q * (1 - tot) > 0)
				{
					nd.merge(s * w + c, q * (1 - tot), Double::sum);
				}
				for (int j = 0; j < n; j++)
				{
					if (!(p[j] > 0))
					{
						continue;
					}
					nd.merge(nextState(s, j, full, done, once) * w + (j == xi ? Math.min(cap, c + 1) : c), q * p[j], Double::sum);
				}
			}
			dist = nd;
		}
		double[] out = new double[w + 1];
		for (Map.Entry<Integer, Double> e : dist.entrySet())
		{
			int c = e.getKey() % w;
			for (int i = 0; i <= c; i++)
			{
				out[i] += e.getValue();
			}
		}
		out[w] = expected;
		return out;
	}

	/**
	 * The analysis of one item in one boss.
	 */
	public static final class ItemAnalysis
	{
		public String name;
		public int K;
		public SeqInfo seq;
		public int pity;
		public int O;
		public int unknown;
		public Integer hits;
		public boolean once;
		public double pNext;
		public boolean collected;
		public int need;
		public Double expected;
		public Double pct;
		public Double exceed;
		public Double cum;
		/**
		 * P(at least one more copy than the player has, from all their kills): how likely it is that the next copy would have come by
		 * now.  The kills made beyond the drop rate carry over to the next copy, so a drop does not send this back to zero.  Equal to
		 * {@link #cum} while the player has none; null before the first kill and for a drop that is already collected.
		 */
		public Double another;
		public Label label;
		public int streakFrom;
		public int streak;
		public double meter;
		/**
		 * False when the player has copies but none with a kill number, so nobody knows how long ago the last one was:
		 * {@link #streak} and {@link #meter} then count from the first kill and say nothing true.
		 */
		public boolean streakKnown = true;
		public final Map<Integer, Double> fwd = new LinkedHashMap<>();
		public Map<Double, Double> kto;
		public int window;
	}

	public static ItemAnalysis analyse(Model m, String name, int window)
	{
		final Boss boss = m.boss;
		final int K = m.K;
		final int win = window > 0 ? window : 50;
		ItemMeta meta = boss.meta().get(name);
		final SeqInfo seq = meta != null ? meta.seq : null;
		ItemAnalysis out = new ItemAnalysis();
		out.name = name;
		out.K = K;
		out.seq = seq;
		out.pity = meta != null ? meta.pity : 0;
		// copies and slot hits
		int O = m.copies(name);
		int unk = m.unknown(name);
		Integer H = null;
		List<String> members = null;
		List<Integer> knownAll = m.known(name);
		if (seq != null)
		{
			members = new ArrayList<>();
			for (Row r : slotMembers(boss, seq.slot))
			{
				members.add(r.name);
			}
			H = slotHits(m, boss, seq.slot);
			knownAll = new ArrayList<>();
			unk = 0;
			for (String n : members)
			{
				knownAll.addAll(m.known(n));
				unk += m.unknown(n);
			}
			Collections.sort(knownAll);
		}
		out.O = O;
		out.unknown = unk;
		out.hits = H;
		// dynamic "once" detection: probability with the item owned
		double pOwned = m.pAt(name, K + 1, 1);
		double pUn = m.pAt(name, K + 1, 0);
		out.once = pUn > 0 && pOwned == 0 && seq == null;
		out.pNext = m.pNext(name);
		out.collected = out.once && O > 0;
		// number of hits needed for the next copy
		int d = 1;
		if (seq != null)
		{
			int l0 = seq.len;
			d = (((seq.n - 1 - H) % l0) + l0) % l0 + 1;
		}
		out.need = d;
		final int dd = d;
		// career
		int lastKnown = knownAll.isEmpty() ? 0 : knownAll.get(knownAll.size() - 1);
		final String runName = seq != null ? members.get(0) : name;
		final List<Run> allRuns = K > 0 ? m.runsFor(runName, 1, K) : new ArrayList<Run>();
		out.expected = null;
		out.pct = null;
		final List<String> grp = seq == null ? nodupGroupOf(m, name) : null;
		if (K > 0)
		{
			java.util.function.IntToDoubleFunction needHits = mm -> mm <= 0 ? 0 : (seq != null ? seq.n + (mm - 1) * seq.len : mm);
			java.util.function.IntToDoubleFunction ge = mm -> mm <= 0 ? 1 : atLeast((int) needHits.applyAsDouble(mm), allRuns);
			if (grp != null && !out.once)
			{
				final double[] c = nodupCareer(m, name, grp, K, O + 1, nodupOnce(m, name));
				final int cap = O + 1;
				ge = mm -> mm <= 0 ? 1 : mm <= cap ? c[mm] : 0;
				out.pct = 1 - 0.5 * (ge.applyAsDouble(O) + ge.applyAsDouble(O + 1));
				out.another = ge.applyAsDouble(O + 1);
				out.expected = c[cap + 1];
			}
			else if (out.once && O > 0)
			{
				List<Integer> kn = m.known(name);
				if (!kn.isEmpty())
				{
					int k1 = kn.get(0);
					List<Run> r1 = m.runsFor(name, 1, k1 - 1);
					double pk = m.pAt(name, k1, 0);
					double sv = Math.exp(logSurv(r1));
					out.pct = sv * (1 - pk) + 0.5 * sv * pk; // P(T>k1) + 1/2 P(T=k1): first-passage mid-p
				}
				out.expected = sumP(allRuns);
			}
			else
			{
				out.pct = 1 - 0.5 * (ge.applyAsDouble(O) + ge.applyAsDouble(O + 1));
				out.another = ge.applyAsDouble(O + 1);
				if (seq != null)
				{
					double e = 0;
					for (int mm = 1; mm <= O + 12; mm++)
					{
						e += ge.applyAsDouble(mm);
					}
					out.expected = e;
				}
				else
				{
					out.expected = sumP(allRuns);
				}
			}
			out.exceed = ge.applyAsDouble(O); // P(at least as many as you have)
			out.cum = ge.applyAsDouble(1); // P(at least one over all your kills, whenever your drops came): never resets
		}
		out.label = luckLabel(out.pct);
		// progress meter: kills since the last receipt (hit of the slot for sequence items)
		out.streakFrom = lastKnown;
		out.streak = Math.max(0, K - lastKnown);
		out.streakKnown = !knownAll.isEmpty() || unk == 0;
		if (out.collected)
		{
			out.meter = 1;
		}
		else
		{
			List<Run> sr = out.streak > 0 ? m.runsFor(runName, lastKnown + 1, K) : new ArrayList<Run>();
			out.meter = out.streak > 0 ? atLeast(dd, sr) : 0;
		}
		// forward looking
		if (grp != null && !out.collected)
		{
			nodupForward(m, name, grp, K, new int[] {win, 10, 100, 500, 1000}, new double[] {0.5, 0.9, 0.99}, out, nodupOnce(m, name));
		}
		else if (!out.collected)
		{
			int[] ns = {win, 10, 100, 500, 1000};
			for (int n : ns)
			{
				if (!out.fwd.containsKey(n))
				{
					out.fwd.put(n, atLeast(dd, m.runsFor(runName, K + 1, K + n)));
				}
			}
			out.kto = new LinkedHashMap<>();
			for (double c : new double[] {0.5, 0.9, 0.99})
			{
				out.kto.put(c, killsTo(m, runName, dd, c, K));
			}
		}
		out.window = win;
		return out;
	}

	// ------------------------------------------------------------------ "any unique" synthetic item
	public static final class AnyUnique
	{
		public int O;
		public double expected;
		public double pct;
		public Label label;
		public double meter;
		public double cum;
		/**
		 * P(at least one more of these than the player has, from all their kills); see {@link ItemAnalysis#another}.
		 */
		public double another;
		public int streak;
		/**
		 * False when the player has uniques but none with a kill number (see {@link ItemAnalysis#streakKnown}).
		 */
		public boolean streakKnown = true;
		public final Map<Integer, Double> fwd = new LinkedHashMap<>();
		public int window;
		public double pNext;
		public int events;
	}

	/**
	 * One event per kill: "at least one of these items drops".  Computed inside the drop-table maths so items that cannot drop together
	 * (one table roll gives one item) are not treated as independent.  A sequence slot (the vestige) is one event for all its members.
	 */
	public static AnyUnique anyUnique(Model m, List<String> names, int window)
	{
		final Boss boss = m.boss;
		final int K = m.K;
		final int win = window > 0 ? window : 50;
		Map<String, ItemMeta> meta = boss.meta();
		Set<String> seen = new LinkedHashSet<>();
		List<String> evs = new ArrayList<>();
		// an item that only drops from a follow table (Sire's Font of Consumption, rolled once per Unsired) is part of its source's event
		Map<String, List<String>> fm = Engine.followMap(boss);
		Set<String> nset = new LinkedHashSet<>(names);
		Map<String, List<String>> kids = new HashMap<>();
		for (String n : names)
		{
			List<String> fs = fm.get(n);
			if (fs != null)
			{
				for (String f : fs)
				{
					if (nset.contains(f))
					{
						kids.computeIfAbsent(f, k -> new ArrayList<>()).add(n);
					}
				}
			}
		}
		for (String n : names)
		{
			ItemMeta mt = meta.get(n);
			if (mt == null || isKid(fm, nset, n))
			{
				continue;
			}
			if (mt.seq != null)
			{
				if (seen.contains(mt.seq.slot))
				{
					continue;
				}
				seen.add(mt.seq.slot);
			}
			evs.add(n);
		}
		if (evs.isEmpty() || K <= 0)
		{
			return null;
		}
		// O = kills that gave at least one of these items: copies on the same kill count once, copies with no kill number count one each,
		// and a slot's unlogged earlier hits are filled in
		int lastKnown = 0;
		int unk = 0;
		int extra = 0;
		Set<String> slots = new LinkedHashSet<>();
		Set<Integer> kcs = new LinkedHashSet<>();
		for (String n : names)
		{
			ItemMeta mt = meta.get(n);
			if (mt == null)
			{
				continue;
			}
			if (mt.seq != null)
			{
				if (slots.contains(mt.seq.slot))
				{
					continue;
				}
				slots.add(mt.seq.slot);
				int sumc = 0;
				for (Row r : slotMembers(boss, mt.seq.slot))
				{
					lastKnown = noteKnown(m, r.name, kcs, lastKnown);
					unk += m.unknown(r.name);
					sumc += m.copies(r.name);
				}
				extra += Math.max(0, slotHits(m, boss, mt.seq.slot) - sumc);
			}
			else
			{
				lastKnown = noteKnown(m, n, kcs, lastKnown);
				if (isKid(fm, nset, n))
				{
					continue;
				}
				int u = m.unknown(n);
				List<String> ks = kids.get(n);
				if (ks != null)
				{
					int t = 0;
					for (String k : ks)
					{
						t += m.unknown(k); // each Unsired gives one follow-table roll
					}
					u = Math.max(u, t);
				}
				unk += u;
			}
		}
		AnyUnique out = new AnyUnique();
		out.O = kcs.size() + unk + extra;
		List<Run> runsAll = m.runsForAny(evs, 1, K);
		out.pct = 1 - 0.5 * (atLeast(out.O, runsAll) + atLeast(out.O + 1, runsAll));
		List<Run> sr = K - lastKnown > 0 ? m.runsForAny(evs, lastKnown + 1, K) : new ArrayList<Run>();
		out.meter = K - lastKnown > 0 ? atLeast(1, sr) : 0;
		out.cum = atLeast(1, runsAll);
		out.another = atLeast(out.O + 1, runsAll);
		int[] ns = {win, 10, 100, 500};
		for (int n : ns)
		{
			out.fwd.put(n, atLeast(1, m.runsForAny(evs, K + 1, K + n)));
		}
		out.pNext = m.pAtAny(evs, K + 1);
		out.expected = sumP(runsAll);
		out.label = luckLabel(out.pct);
		out.streak = K - lastKnown;
		out.streakKnown = !kcs.isEmpty() || unk == 0;
		out.window = win;
		out.events = evs.size();
		return out;
	}

	private static boolean isKid(Map<String, List<String>> fm, Set<String> nset, String n)
	{
		List<String> fs = fm.get(n);
		if (fs == null)
		{
			return false;
		}
		for (String f : fs)
		{
			if (nset.contains(f))
			{
				return true;
			}
		}
		return false;
	}

	private static int noteKnown(Model m, String n, Set<Integer> kcs, int lastKnown)
	{
		List<Integer> ks = m.known(n);
		kcs.addAll(ks);
		if (!ks.isEmpty())
		{
			return Math.max(lastKnown, ks.get(ks.size() - 1));
		}
		return lastKnown;
	}

	// ------------------------------------------------------------------ pooled item (several bosses drop it)
	public static final class PoolMember
	{
		public final Model m;
		public final int snapKc;

		public PoolMember(Model m, int snapKc)
		{
			this.m = m;
			this.snapKc = snapKc;
		}
	}

	public static final class PoolPer
	{
		public String id;
		public String name;
		public int K;
		public int streak;
		public int copies;
	}

	public static final class Pool
	{
		public String name;
		public int O;
		public double expected;
		public double pct;
		public Label label;
		public double meter;
		public double cum;
		/**
		 * P(at least one more copy than the player has, over all these bosses' kills); see {@link ItemAnalysis#another}.
		 */
		public double another;
		public long streak;
		/**
		 * False when the player has copies but none with a kill number (see {@link ItemAnalysis#streakKnown}).
		 */
		public boolean streakKnown = true;
		public long kills;
		public final List<PoolPer> per = new ArrayList<>();
		public int window;
	}

	public static Pool analysePool(List<PoolMember> members, String name, int window)
	{
		final int win = window > 0 ? window : 50;
		int O = 0;
		int unknown = 0;
		boolean anyKnown = false;
		double expected = 0;
		List<List<Run>> runsAll = new ArrayList<>();
		List<List<Run>> sr = new ArrayList<>();
		List<PoolPer> per = new ArrayList<>();
		for (PoolMember pm : members)
		{
			Model m = pm.m;
			int K = m.K;
			O += m.copies(name);
			unknown += m.unknown(name);
			anyKnown |= !m.known(name).isEmpty();
			List<Run> ra = K > 0 ? m.runsFor(name, 1, K) : new ArrayList<Run>();
			runsAll.add(ra);
			expected += sumP(ra);
			int from = Math.min(K, Math.max(0, pm.snapKc));
			List<Run> rs = K - from > 0 ? m.runsFor(name, from + 1, K) : new ArrayList<Run>();
			sr.add(rs);
			PoolPer pp = new PoolPer();
			pp.id = m.boss.id;
			pp.name = m.boss.name;
			pp.K = K;
			pp.streak = K - from;
			pp.copies = m.copies(name);
			per.add(pp);
		}
		List<Run> all = mergeRuns(runsAll);
		List<Run> st = mergeRuns(sr);
		long kills = countKills(all);
		if (kills == 0)
		{
			return null;
		}
		Pool out = new Pool();
		out.name = name;
		out.O = O;
		out.expected = expected;
		out.pct = 1 - 0.5 * (atLeast(O, all) + atLeast(O + 1, all));
		out.label = luckLabel(out.pct);
		out.meter = atLeast(1, st);
		out.cum = atLeast(1, all);
		out.another = atLeast(O + 1, all);
		out.streak = countKills(st);
		out.streakKnown = anyKnown || unknown == 0;
		out.kills = kills;
		out.per.addAll(per);
		out.window = win;
		return out;
	}

	/**
	 * Convenience for tests: the chance list as plain strings.
	 */
	static String describe(List<Run> runs)
	{
		return Arrays.toString(runs.stream().map(r -> r.n + "x" + r.p).toArray());
	}
}
