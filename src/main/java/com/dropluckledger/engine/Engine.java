package com.dropluckledger.engine;

import com.dropluckledger.engine.Data.Boss;
import com.dropluckledger.engine.Data.Gate;
import com.dropluckledger.engine.Data.Param;
import com.dropluckledger.engine.Data.Row;
import com.dropluckledger.engine.Data.Seq;
import com.dropluckledger.engine.Data.Shared;
import com.dropluckledger.engine.Data.Sub;
import com.dropluckledger.engine.Data.Table;
import com.dropluckledger.engine.Data.VarClause;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Per-kill drop probabilities of a boss.  This is a line-by-line port of the web app's engine.js
 * (the part that computes chances; the drop simulator is intentionally not part of the plugin).
 * See SPEC.md of the web app for the meaning of the table options.
 */
public final class Engine
{
	private Engine()
	{
	}

	/**
	 * What the account has so far, used by rows whose chance depends on it (pity timers, no-duplicate groups, owned()).
	 */
	public interface Ctx
	{
		int owned(Row row);

		double since(Row row);
	}

	// ------------------------------------------------------------------ environment
	public static Map<String, Object> defaultParams(Boss boss)
	{
		Map<String, Object> o = new LinkedHashMap<>();
		for (Param p : boss.params)
		{
			o.put(p.name, p.def);
		}
		return o;
	}

	private static boolean jsTruthy(Object v)
	{
		if (v == null)
		{
			return false;
		}
		if (v instanceof Boolean)
		{
			return (Boolean) v;
		}
		if (v instanceof Number)
		{
			double d = ((Number) v).doubleValue();
			return d != 0 && !Double.isNaN(d);
		}
		if (v instanceof CharSequence)
		{
			return ((CharSequence) v).length() > 0;
		}
		return true;
	}

	private static double jsNumber(Object v)
	{
		if (v == null)
		{
			return 0;
		}
		if (v instanceof Boolean)
		{
			return (Boolean) v ? 1 : 0;
		}
		if (v instanceof Number)
		{
			return ((Number) v).doubleValue();
		}
		String s = v.toString().trim();
		if (s.isEmpty())
		{
			return 0;
		}
		try
		{
			return Double.parseDouble(s);
		}
		catch (NumberFormatException e)
		{
			return Double.NaN;
		}
	}

	/**
	 * Builds the variables an expression can read for one boss, one set of boss settings and a kill count.
	 *
	 * @param values  boss settings by name (missing ones use the data's default)
	 * @param globals account-wide switches (rowi = ring of wealth, lq = Legends' Quest)
	 */
	public static Env makeEnv(Boss boss, Map<String, Object> values, Map<String, Double> globals, double kc)
	{
		Env env = new Env();
		env.put("rowi", 0);
		env.put("lq", 1);
		env.put("kc", kc);
		env.put("owned", 0);
		env.put("ri", 0);
		if (globals != null)
		{
			for (Map.Entry<String, Double> e : globals.entrySet())
			{
				env.put(e.getKey(), e.getValue());
			}
		}
		for (Param p : boss.params)
		{
			Object v = values != null && values.containsKey(p.name) ? values.get(p.name) : p.def;
			if ("bool".equals(p.type))
			{
				env.put(p.name, jsTruthy(v) ? 1 : 0);
			}
			else if ("int".equals(p.type))
			{
				env.put(p.name, jsNumber(v));
			}
			else if ("select".equals(p.type))
			{
				String sv = v instanceof String ? (String) v : null;
				env.put(p.name, sv == null ? -1 : p.options.indexOf(sv));
				for (String o : p.options)
				{
					env.put(o, o.equals(sv) ? 1 : 0);
				}
			}
		}
		for (Data.Derived d : boss.derived)
		{
			env.put(d.name, d.f.eval(env));
		}
		return env;
	}

	// ------------------------------------------------------------------ row probabilities
	/**
	 * Absolute probability per attempt of a row; null for "rest" rows (whatever is left over) and unknown rates.
	 */
	public static Double rowBase(Row row, Env env, Ctx ctx)
	{
		if (row.ifFn != null && !Expr.truthy(row.ifFn.eval(env)))
		{
			return 0.0;
		}
		if (row.expr != null)
		{
			env.put("owned", ctx != null ? ctx.owned(row) : 0);
			double v = row.expr.eval(env);
			env.put("owned", 0);
			return Math.max(0, v);
		}
		if (row.var != null)
		{
			VarClause best = null;
			for (VarClause v : row.var)
			{
				boolean all = true;
				for (String c : v.conds)
				{
					boolean ok = c.charAt(0) == '!' ? !env.truthy(c.substring(1)) : env.truthy(c);
					if (!ok)
					{
						all = false;
						break;
					}
				}
				if (all && (best == null || v.conds.size() > best.conds.size()))
				{
					best = v;
				}
			}
			if (best != null)
			{
				return Math.max(0, best.rate.eval(env));
			}
		}
		if (row.flagged("pity") && ctx != null)
		{
			if (ctx.since(row) >= row.pity - 1)
			{
				return 1.0;
			}
		}
		if (row.p == null)
		{
			return null;
		}
		return row.p;
	}

	/**
	 * Independent attempts for tertiaries and pets (attribute trials=expr).
	 */
	private static Double withTrials(Row row, Double p, Env env)
	{
		String t = row.attrs.get("trials");
		if (t == null || t.isEmpty() || p == null)
		{
			return p;
		}
		double n = Expr.parse(t).eval(env);
		return 1 - Math.pow(1 - p, n);
	}

	private static int cnt(Ctx ctx, Row row)
	{
		return ctx != null ? ctx.owned(row) : 0;
	}

	private static boolean truthy(Double d)
	{
		return d != null && d != 0 && !Double.isNaN(d);
	}

	// ------------------------------------------------------------------ table expansion
	static final class Out
	{
		final String name;
		final double pm;
		final Row row;

		Out(String name, double pm, Row row)
		{
			this.name = name;
			this.pm = pm;
			this.row = row;
		}
	}

	public static final class Member
	{
		public final String name;
		public double pm;
		public final Row row;
		public String xor;
		public Integer seqN;
		public int[] seqNs;

		Member(String name, double pm, Row row)
		{
			this.name = name;
			this.pm = pm;
			this.row = row;
		}
	}

	public static final class Entry
	{
		public Double p;
		public Double k;
		public final List<Member> members = new ArrayList<>();
		public int row = -1;
		public String nodup;
		public String slot;
		public boolean bundle;
		public String shared;
	}

	private static Shared sharedLookup(Data data, String id)
	{
		Shared s = data.sharedById.get(id);
		if (s == null)
		{
			throw new IllegalStateException("unknown shared table " + id);
		}
		return s;
	}

	private static double nz(Double d)
	{
		return d == null ? 0 : d;
	}

	static void expandShared(Data data, Shared s, Env env, Ctx ctx, double mass, List<Out> out)
	{
		Table t = s.tables.get(0);
		List<Row> rows = new ArrayList<>();
		for (Row r : t.items)
		{
			if (!(env.truthy("rowi") && r.flagged("rowremove")))
			{
				rows.add(r);
			}
		}
		Double[] shares = new Double[rows.size()];
		for (int i = 0; i < rows.size(); i++)
		{
			Row r = rows.get(i);
			shares[i] = r.flag.equals("+") ? Double.valueOf(0) : rowBase(r, env, ctx);
		}
		int restIdx = -1;
		double sum = 0;
		boolean hasAbs = t.abs != null && t.abs != 0 && !Double.isNaN(t.abs);
		for (int i = 0; i < rows.size(); i++)
		{
			if (shares[i] == null)
			{
				restIdx = i;
			}
			else
			{
				if (hasAbs)
				{
					shares[i] = shares[i] / t.abs;
				}
				sum += shares[i];
			}
		}
		for (Gate g : t.gates)
		{
			sum += g.p;
		}
		if (restIdx >= 0)
		{
			shares[restIdx] = Math.max(0, 1 - sum);
			sum += shares[restIdx];
		}
		double f = sum > 0 ? 1 / sum : 0;
		for (int i = 0; i < rows.size(); i++)
		{
			Row r = rows.get(i);
			if (r.flag.equals("+") || !truthy(shares[i]))
			{
				continue;
			}
			out.add(new Out(r.name, mass * shares[i] * f, r));
		}
		for (Gate g : t.gates)
		{
			double m = mass * g.p * f;
			if (g.opt("lq") && !env.truthy("lq"))
			{
				out.add(new Out("Nature talisman", m, null));
				continue;
			}
			expandShared(data, sharedLookup(data, g.table), env, ctx, m, out);
		}
	}

	private static String nonEmpty(String s)
	{
		return s == null || s.isEmpty() ? null : s;
	}

	/**
	 * The mutually exclusive outcomes of one table for one attempt.
	 */
	static List<Entry> expandTable(Data data, Table t, Env env, Ctx ctx, boolean isBase)
	{
		List<Row> rows = t.items;
		int n = rows.size();
		List<Integer> leaders = new ArrayList<>();
		for (int i = 0; i < n; i++)
		{
			if (!rows.get(i).flag.equals("+"))
			{
				leaders.add(i);
			}
		}
		Double[] base = new Double[n];
		for (int i = 0; i < n; i++)
		{
			base[i] = rowBase(rows.get(i), env, ctx);
		}
		// sequence slots: the first row (lowest n) carries the probability, the others are members of the same entry
		Map<String, Integer> slotLead = new HashMap<>();
		for (int i : leaders)
		{
			Seq s = rows.get(i).seq;
			if (s != null && (!slotLead.containsKey(s.slot) || s.n < rows.get(slotLead.get(s.slot)).seq.n))
			{
				slotLead.put(s.slot, i);
			}
		}
		List<Entry> entries = new ArrayList<>();
		Map<String, Entry> slotEntry = new HashMap<>();
		for (int i : leaders)
		{
			Row r = rows.get(i);
			Seq s = r.seq;
			if (s != null && slotLead.get(s.slot) != i)
			{
				continue;
			}
			Entry e = new Entry();
			e.p = base[i];
			e.k = r.cancels;
			e.row = i;
			e.nodup = nonEmpty(r.attrs.get("nodup"));
			if (s != null)
			{
				e.slot = s.slot;
				slotEntry.put(s.slot, e);
			}
			entries.add(e);
			e.members.add(new Member(r.name, nz(base[i]), r));
			for (int j = i + 1; j < n && rows.get(j).flag.equals("+"); j++)
			{
				e.members.add(new Member(rows.get(j).name, nz(base[j]), rows.get(j)));
			}
		}
		for (int i : leaders)
		{
			Seq s = rows.get(i).seq;
			if (s != null && slotLead.get(s.slot) != i)
			{
				Entry e = slotEntry.get(s.slot);
				Member m = new Member(rows.get(i).name, nz(e.p), rows.get(i));
				m.seqN = s.n;
				m.seqNs = s.ns;
				e.members.add(m);
			}
		}
		for (Entry e : entries)
		{
			if (e.slot != null)
			{
				Member m0 = e.members.get(0);
				m0.seqN = m0.row.seq.n;
				m0.seqNs = m0.row.seq.ns;
				for (Member m : e.members)
				{
					m.pm = nz(e.p);
				}
			}
		}
		// no-duplicate groups: only the copies you hold the fewest of can drop; the group's total chance is kept
		Map<String, List<Entry>> groups = new LinkedHashMap<>();
		for (Entry e : entries)
		{
			if (e.nodup != null && e.p != null)
			{
				groups.computeIfAbsent(e.nodup, k -> new ArrayList<>()).add(e);
			}
		}
		for (List<Entry> es : groups.values())
		{
			int[] counts = new int[es.size()];
			int mn = Integer.MAX_VALUE;
			double tot = 0;
			for (int i = 0; i < es.size(); i++)
			{
				counts[i] = cnt(ctx, rows.get(es.get(i).row));
				mn = Math.min(mn, counts[i]);
				tot += es.get(i).p;
			}
			double se = 0;
			for (int i = 0; i < es.size(); i++)
			{
				if (counts[i] == mn)
				{
					se += es.get(i).p;
				}
			}
			for (int i = 0; i < es.size(); i++)
			{
				Entry e = es.get(i);
				double np = counts[i] == mn && se > 0 ? e.p * tot / se : 0;
				for (Member m : e.members)
				{
					m.pm = np;
				}
				e.p = np;
			}
		}
		// gated shared tables and sub-tables (absolute masses)
		List<Entry> extra = new ArrayList<>();
		for (Gate g : t.gates)
		{
			List<Out> out = new ArrayList<>();
			expandShared(data, sharedLookup(data, g.table), env, ctx, g.p, out);
			for (Out o : out)
			{
				Entry e = new Entry();
				e.p = o.pm;
				e.k = null;
				e.members.add(new Member(o.name, o.pm, o.row));
				e.shared = g.table;
				extra.add(e);
			}
		}
		Set<String> seenAlt = new HashSet<>();
		for (Sub sb : t.subs)
		{
			String alt = nonEmpty(sb.opts.get("alt"));
			if (sb.ifFn != null && !Expr.truthy(sb.ifFn.eval(env)))
			{
				continue;
			}
			if (alt != null)
			{
				if (seenAlt.contains(alt))
				{
					continue;
				}
				seenAlt.add(alt);
			}
			boolean sbBundle = nonEmpty(sb.opts.get("bundle")) != null;
			Double[] sp = new Double[sb.items.size()];
			for (int i = 0; i < sp.length; i++)
			{
				sp[i] = rowBase(sb.items.get(i), env, ctx);
			}
			if (sbBundle)
			{
				Entry e = new Entry();
				e.p = sb.p;
				e.k = null;
				e.bundle = true;
				for (int i = 0; i < sp.length; i++)
				{
					Row r = sb.items.get(i);
					Member m = new Member(r.name, Math.min(nz(sp[i]), sb.p), r);
					m.xor = nonEmpty(r.attrs.get("xor"));
					e.members.add(m);
				}
				extra.add(e);
			}
			else
			{
				Entry lead = null;
				for (int i = 0; i < sp.length; i++)
				{
					Row r = sb.items.get(i);
					if (r.flag.equals("+") && lead != null)
					{
						lead.members.add(new Member(r.name, nz(sp[i]), r));
						continue;
					}
					lead = new Entry();
					lead.p = nz(sp[i]);
					lead.k = r.cancels;
					lead.members.add(new Member(r.name, nz(sp[i]), r));
					extra.add(lead);
				}
			}
		}
		// totals
		double entriesSum = 0;
		for (Entry e : entries)
		{
			entriesSum += nz(e.p);
		}
		double extraSum = 0;
		for (Entry e : extra)
		{
			extraSum += e.p;
		}
		double total = entriesSum + extraSum;
		if (t.gateFn != null)
		{
			double g = Math.min(1, Math.max(0, t.gateFn.eval(env)));
			double s = entriesSum;
			double sc = s > 0 ? g / s : 0;
			for (Entry e : entries)
			{
				e.p = nz(e.p) * sc;
				for (Member m : e.members)
				{
					m.pm = m.pm * sc;
				}
			}
		}
		else
		{
			List<Entry> restE = new ArrayList<>();
			for (Entry e : entries)
			{
				if (e.p == null)
				{
					restE.add(e);
				}
			}
			if (!restE.isEmpty())
			{
				double r = Math.max(0, 1 - total);
				for (Entry e : restE)
				{
					e.p = r / restE.size();
					for (Member m : e.members)
					{
						m.pm = e.p;
					}
				}
				total += r;
			}
			// base tables always sum to 1 (wiki rounding); any table that exceeds 1 is scaled down so the maths agree
			String normalize = t.opts.get("normalize");
			if ((normalize != null && !normalize.isEmpty()) || isBase || total > 1)
			{
				double sc = total > 0 ? 1 / total : 1;
				for (Entry e : entries)
				{
					e.p = e.p * sc;
					for (Member m : e.members)
					{
						m.pm *= sc;
					}
				}
				for (Entry e : extra)
				{
					e.p = e.p * sc;
					for (Member m : e.members)
					{
						m.pm *= sc;
					}
				}
			}
		}
		List<Entry> all = new ArrayList<>(entries);
		all.addAll(extra);
		if (t.multFn != null)
		{
			double mu = t.multFn.eval(env);
			for (Entry e : all)
			{
				e.p = e.p * mu;
				for (Member m : e.members)
				{
					m.pm *= mu;
				}
			}
		}
		// item-level cancels default to the table level
		for (Entry e : all)
		{
			if (e.k == null)
			{
				e.k = t.cancels;
			}
		}
		List<Entry> res = new ArrayList<>();
		for (Entry e : all)
		{
			boolean named = false;
			for (Member m : e.members)
			{
				if (m.name != null && !m.name.isEmpty())
				{
					named = true;
					break;
				}
			}
			if (e.p > 0 || named)
			{
				res.add(e);
			}
		}
		return res;
	}

	// ------------------------------------------------------------------ kill plan
	static final class ChainTable
	{
		Table t;
		boolean par;
		List<Entry> entries;
		Expr nFn;
		boolean isBase;
	}

	static final class Chain
	{
		List<ChainTable> tables;
		ChainTable base;
	}

	static final class Plan
	{
		boolean indexMode;
		double[][] rd; // pairs [rolls, weight]
		Chain chain;
		List<Chain> chains;
		Boss boss;
	}

	private static Chain buildChain(Boss boss, Env env, Ctx ctx)
	{
		List<ChainTable> chain = new ArrayList<>();
		for (Table t : boss.tables)
		{
			String follow = t.opts.get("follow");
			if (follow != null && !follow.isEmpty())
			{
				continue;
			}
			if (t.ifFn != null && !Expr.truthy(t.ifFn.eval(env)))
			{
				continue;
			}
			ChainTable ct = new ChainTable();
			ct.t = t;
			ct.par = t.par;
			ct.nFn = t.rollsFn;
			chain.add(ct);
		}
		ChainTable base = null;
		for (ChainTable ct : chain)
		{
			if (!ct.par && !ct.t.pre)
			{
				base = ct;
			}
		}
		for (ChainTable ct : chain)
		{
			ct.isBase = ct == base;
			ct.entries = expandTable(boss.data, ct.t, env, ctx, ct.isBase);
		}
		Chain c = new Chain();
		c.tables = new ArrayList<>();
		for (ChainTable ct : chain)
		{
			if (!ct.isBase)
			{
				c.tables.add(ct);
			}
		}
		c.base = base;
		return c;
	}

	private static double[][] rollsDist(Boss boss, Env env)
	{
		double v = boss.rollsFn != null ? boss.rollsFn.eval(env) : 1;
		double lo = Math.max(0, Math.floor(v + 1e-12));
		double f = Math.max(0, v - lo);
		if (f > 1e-9)
		{
			return new double[][] {{lo, 1 - f}, {lo + 1, f}};
		}
		return new double[][] {{lo, 1}};
	}

	static Plan plan(Boss boss, Env env, Ctx ctx)
	{
		Plan pl = new Plan();
		pl.boss = boss;
		pl.rd = rollsDist(boss, env);
		if (boss.rv != null)
		{
			double maxR = 0;
			for (double[] x : pl.rd)
			{
				maxR = Math.max(maxR, x[0]);
			}
			pl.indexMode = true;
			pl.chains = new ArrayList<>();
			for (int r = 0; r < maxR; r++)
			{
				env.put(boss.rv.name, boss.rv.start + r);
				env.put("ri", r + 1);
				pl.chains.add(buildChain(boss, env, ctx));
			}
			return pl;
		}
		pl.indexMode = false;
		pl.chain = buildChain(boss, env, ctx);
		return pl;
	}

	private static int baseRolls(Chain chain, Env env, int rolls)
	{
		if (chain.base == null)
		{
			return 0;
		}
		Table t = chain.base.t;
		if (t.rollsFn != null)
		{
			return (int) Math.max(0, Math.floor(t.rollsFn.eval(env) + 1e-12));
		}
		return rolls;
	}

	private static int nAttempts(ChainTable t, Env env, int rolls)
	{
		if (t.nFn != null)
		{
			return (int) Math.max(0, Math.floor(t.nFn.eval(env) + 1e-12));
		}
		return t.par ? 1 : rolls;
	}

	private static double kEff(Entry e, ChainTable t)
	{
		return e.k != null ? e.k : (t.par ? 0 : 1);
	}

	/**
	 * Probability of receiving NONE of the named items in one kill with R rolls.
	 */
	static double survChain(Chain chain, Env env, int rolls, Set<String> names, Integer m0Force)
	{
		int m0 = m0Force != null ? m0Force : baseRolls(chain, env, rolls);
		double[] vec = new double[m0 + 1];
		vec[m0] = 1;
		for (ChainTable t : chain.tables)
		{
			TreeMap<Integer, Double> mass = new TreeMap<>();
			double tot = 0;
			for (Entry e : t.entries)
			{
				double a = 0;
				for (Member m : e.members)
				{
					if (names.contains(m.name))
					{
						a = Math.max(a, m.pm);
					}
				}
				a = Math.min(a, e.p);
				tot += e.p;
				double k = kEff(e, t);
				int kk = Double.isInfinite(k) ? 1_000_000_000 : (int) k;
				if (e.p - a > 0)
				{
					mass.merge(kk, e.p - a, Double::sum);
				}
			}
			double miss = Math.max(0, 1 - tot);
			int n = nAttempts(t, env, rolls);
			double[] nv = new double[m0 + 1];
			for (int mm = 0; mm <= m0; mm++)
			{
				double w = vec[mm];
				if (!Expr.truthy(w))
				{
					continue;
				}
				if (!t.par && mm == 0)
				{
					nv[0] += w;
					continue;
				}
				double[] local = new double[mm + 1];
				local[mm] = w;
				int ne = t.par ? n : Math.min(n, mm);
				for (int a = 0; a < ne; a++)
				{
					double[] nx = new double[mm + 1];
					for (int m = 0; m <= mm; m++)
					{
						double x = local[m];
						if (!Expr.truthy(x))
						{
							continue;
						}
						if (!t.par && m == 0)
						{
							nx[0] += x;
							continue;
						}
						nx[m] += x * miss;
						for (Map.Entry<Integer, Double> me : mass.entrySet())
						{
							nx[Math.max(0, m - me.getKey())] += x * me.getValue();
						}
					}
					local = nx;
				}
				for (int m = 0; m <= mm; m++)
				{
					nv[m] += local[m];
				}
			}
			vec = nv;
		}
		if (chain.base != null)
		{
			double q = 0;
			for (Entry e : chain.base.entries)
			{
				double a = 0;
				for (Member m : e.members)
				{
					if (names.contains(m.name))
					{
						a = Math.max(a, m.pm);
					}
				}
				q += Math.min(a, e.p);
			}
			double s = 0;
			double keep = Math.max(0, 1 - q);
			for (int mm = 0; mm <= m0; mm++)
			{
				s += vec[mm] * Math.pow(keep, mm);
			}
			return s;
		}
		double s = 0;
		for (double x : vec)
		{
			s += x;
		}
		return s;
	}

	static double survPlan(Plan pl, Env env, Set<String> names)
	{
		double tot = 0;
		for (double[] rw : pl.rd)
		{
			int rolls = (int) rw[0];
			double w = rw[1];
			double s;
			if (!pl.indexMode)
			{
				s = survChain(pl.chain, env, rolls, names, null);
			}
			else
			{
				s = 1;
				for (int r = 0; r < rolls; r++)
				{
					env.put(pl.boss.rv.name, pl.boss.rv.start + r);
					env.put("ri", r + 1);
					s *= survChain(pl.chains.get(r), env, 1, names, 1);
				}
			}
			tot += w * s;
		}
		return tot;
	}

	private static Set<String> planNames(Plan pl)
	{
		Set<String> set = new LinkedHashSet<>();
		List<Chain> chains = new ArrayList<>();
		if (!pl.indexMode)
		{
			chains.add(pl.chain);
		}
		else
		{
			chains.addAll(pl.chains);
		}
		for (Chain c : chains)
		{
			List<ChainTable> all = new ArrayList<>(c.tables);
			if (c.base != null)
			{
				all.add(c.base);
			}
			for (ChainTable t : all)
			{
				for (Entry e : t.entries)
				{
					for (Member m : e.members)
					{
						set.add(m.name);
					}
				}
			}
		}
		return set;
	}

	// ------------------------------------------------------------------ per-kill item probabilities
	public static final class SeqInfo
	{
		public final String slot;
		public final int n;
		public final int len;

		SeqInfo(String slot, int n, int len)
		{
			this.slot = slot;
			this.n = n;
			this.len = len;
		}
	}

	public static final class ItemProb
	{
		public double p;
		public final List<String> kinds = new ArrayList<>();
		public final List<Row> rows = new ArrayList<>();
		public SeqInfo seq;
	}

	private static void put(Map<String, ItemProb> out, String name, double p, String kind, Row row)
	{
		ItemProb o = out.computeIfAbsent(name, k -> new ItemProb());
		o.p = 1 - (1 - o.p) * (1 - Math.min(1, Math.max(0, p)));
		if (!o.kinds.contains(kind))
		{
			o.kinds.add(kind);
		}
		if (row != null)
		{
			o.rows.add(row);
		}
	}

	/**
	 * Per-kill probability of each item (restricted to onlyNames when given).
	 */
	public static Map<String, ItemProb> itemProbs(Boss boss, Env env, Ctx ctx, Set<String> onlyNames)
	{
		Map<String, ItemProb> out = new LinkedHashMap<>();
		Plan pl = plan(boss, env, ctx);
		for (String n : planNames(pl))
		{
			if (onlyNames != null && !onlyNames.contains(n))
			{
				continue;
			}
			put(out, n, 1 - survPlan(pl, env, java.util.Collections.singleton(n)), "table", null);
		}
		// row metadata: sequence lengths
		Map<String, Integer> slotLen = new HashMap<>();
		for (Table t : boss.tables)
		{
			for (Row r : t.items)
			{
				if (r.seq != null)
				{
					slotLen.merge(r.seq.slot, r.seq.last, Math::max);
				}
			}
		}
		for (Table t : boss.tables)
		{
			for (Row r : t.items)
			{
				if (r.seq != null && out.containsKey(r.name))
				{
					out.get(r.name).seq = new SeqInfo(r.seq.slot, r.seq.n, slotLen.get(r.seq.slot));
				}
			}
		}
		// follow tables (e.g. Abyssal Sire: items rolled when the Unsired is offered)
		for (Table t : boss.tables)
		{
			String src = nonEmpty(t.opts.get("follow"));
			if (src == null || (t.ifFn != null && !Expr.truthy(t.ifFn.eval(env))))
			{
				continue;
			}
			double pSrc = out.containsKey(src) ? out.get(src).p : 0;
			List<Row> rows = new ArrayList<>();
			for (Row r : t.items)
			{
				if (!r.flag.equals("+"))
				{
					rows.add(r);
				}
			}
			Map<String, List<Row>> groups = new LinkedHashMap<>();
			for (Row r : rows)
			{
				String g = nonEmpty(r.attrs.get("nodup"));
				if (g != null)
				{
					groups.computeIfAbsent(g, k -> new ArrayList<>()).add(r);
				}
			}
			IdentityHashMap<Row, Double> adj = new IdentityHashMap<>();
			for (List<Row> rs : groups.values())
			{
				int[] cs = new int[rs.size()];
				int mn = Integer.MAX_VALUE;
				double tot = 0;
				for (int i = 0; i < rs.size(); i++)
				{
					cs[i] = cnt(ctx, rs.get(i));
					mn = Math.min(mn, cs[i]);
					tot += nz(rs.get(i).p);
				}
				double se = 0;
				for (int i = 0; i < rs.size(); i++)
				{
					if (cs[i] == mn)
					{
						se += nz(rs.get(i).p);
					}
				}
				for (int i = 0; i < rs.size(); i++)
				{
					adj.put(rs.get(i), cs[i] == mn && se > 0 ? nz(rs.get(i).p) * tot / se : 0.0);
				}
			}
			for (Row r : rows)
			{
				if (onlyNames != null && !onlyNames.contains(r.name))
				{
					continue;
				}
				Double sh = adj.containsKey(r) ? adj.get(r) : rowBase(r, env, ctx);
				if (truthy(sh))
				{
					put(out, r.name, pSrc * sh, "follow", r);
				}
			}
		}
		// tertiaries and pets
		for (Row r : boss.tert)
		{
			if (onlyNames != null && !onlyNames.contains(r.name))
			{
				continue;
			}
			Double p = withTrials(r, rowBase(r, env, ctx), env);
			if (truthy(p))
			{
				put(out, r.name, p, "tert", r);
			}
		}
		for (Row r : boss.pets)
		{
			if (onlyNames != null && !onlyNames.contains(r.name))
			{
				continue;
			}
			Double p = withTrials(r, rowBase(r, env, ctx), env);
			if (truthy(p))
			{
				put(out, r.name, p, "pet", r);
			}
		}
		return out;
	}

	/**
	 * Items that can only drop from a follow table, mapped to the item(s) that trigger that table
	 * (Abyssal Sire: Unsired -> Font of Consumption).
	 */
	public static Map<String, List<String>> followMap(Boss boss)
	{
		Map<String, List<String>> src = new LinkedHashMap<>();
		Set<String> other = new HashSet<>();
		for (Table t : boss.tables)
		{
			String f = nonEmpty(t.opts.get("follow"));
			for (Row r : t.items)
			{
				if (f != null)
				{
					src.computeIfAbsent(r.name, k -> new ArrayList<>()).add(f);
				}
				else
				{
					other.add(r.name);
				}
			}
		}
		Map<String, List<String>> out = new LinkedHashMap<>();
		for (Map.Entry<String, List<String>> e : src.entrySet())
		{
			if (!other.contains(e.getKey()))
			{
				out.put(e.getKey(), e.getValue());
			}
		}
		return out;
	}

	/**
	 * P(at least one of targets in one kill).  Items off the kill's tables exclude or otherwise depend on each other, so they are
	 * combined inside one pass (exact); tertiaries, pets and follow-up rolls are independent events and are multiplied in.
	 * sources = names other rolls depend on (follow-table sources) that must be evaluated but are not targets.
	 */
	public static double anyProb(Boss boss, Env env, Ctx ctx, Set<String> targets, Collection<String> sources)
	{
		Set<String> need = new LinkedHashSet<>(targets);
		if (sources != null)
		{
			need.addAll(sources);
		}
		Map<String, ItemProb> all = itemProbs(boss, env, ctx, need);
		Set<String> tbl = new LinkedHashSet<>();
		double q = 1;
		Map<String, List<String>> src = new HashMap<>();
		for (Table t : boss.tables)
		{
			String f = nonEmpty(t.opts.get("follow"));
			if (f != null)
			{
				for (Row r : t.items)
				{
					src.computeIfAbsent(r.name, k -> new ArrayList<>()).add(f);
				}
			}
		}
		for (String n : targets)
		{
			ItemProb o = all.get(n);
			if (o == null)
			{
				continue;
			}
			if (o.kinds.size() == 1 && o.kinds.get(0).equals("table"))
			{
				tbl.add(n);
				continue;
			}
			if (o.kinds.size() == 1 && o.kinds.get(0).equals("follow"))
			{
				boolean viaTarget = false;
				List<String> fs = src.get(n);
				if (fs != null)
				{
					for (String f : fs)
					{
						if (targets.contains(f))
						{
							viaTarget = true;
							break;
						}
					}
				}
				if (viaTarget)
				{
					continue; // only rolled after a source that is itself in the set
				}
			}
			q *= 1 - o.p;
		}
		if (!tbl.isEmpty())
		{
			q *= survPlan(plan(boss, env, ctx), env, tbl);
		}
		return 1 - q;
	}

	// ------------------------------------------------------------------ item catalogue (static metadata)
	public static final class ItemMeta
	{
		public final String name;
		public final List<String> flags = new ArrayList<>();
		public String pool;
		public boolean hidden;
		public String group;
		public SeqInfo seq;
		public final List<String> where = new ArrayList<>();
		public boolean noany;
		public int pity;
		public String note;

		ItemMeta(String name)
		{
			this.name = name;
		}

		public boolean isUnique()
		{
			return flags.contains("U");
		}

		public boolean isPet()
		{
			return flags.contains("P");
		}
	}

	public static Map<String, ItemMeta> itemMeta(Boss boss)
	{
		Map<String, ItemMeta> meta = new LinkedHashMap<>();
		Map<String, Integer> slotLen = new HashMap<>();
		for (Table t : boss.tables)
		{
			for (Row r : t.items)
			{
				if (r.seq != null)
				{
					slotLen.merge(r.seq.slot, r.seq.last, Math::max);
				}
			}
		}
		for (Table t : boss.tables)
		{
			for (Row r : t.items)
			{
				doRow(meta, slotLen, r, t.id);
			}
			for (Sub sb : t.subs)
			{
				for (Row r : sb.items)
				{
					doRow(meta, slotLen, r, t.id + ":" + sb.id);
				}
			}
			for (Gate g : t.gates)
			{
				List<Out> out = new ArrayList<>();
				Env env = makeEnv(boss, defaultParams(boss), new HashMap<>(), 0);
				env.put("rowi", 0);
				expandShared(boss.data, sharedLookup(boss.data, g.table), env, null, 1, out);
				for (Out o : out)
				{
					ItemMeta m = meta.computeIfAbsent(o.name, ItemMeta::new);
					if (!m.where.contains(g.table))
					{
						m.where.add(g.table);
					}
				}
			}
		}
		for (Row r : boss.tert)
		{
			doRow(meta, slotLen, r, "tertiary");
		}
		for (Row r : boss.pets)
		{
			ItemMeta m = meta.computeIfAbsent(r.name, ItemMeta::new);
			if (!m.flags.contains("P"))
			{
				m.flags.add("P");
			}
			m.where.add("pet");
			if (r.flagged("note"))
			{
				m.note = r.attr("note");
			}
		}
		return meta;
	}

	private static void doRow(Map<String, ItemMeta> meta, Map<String, Integer> slotLen, Row r, String where)
	{
		ItemMeta m = meta.computeIfAbsent(r.name, ItemMeta::new);
		if (r.flag != null && !r.flag.isEmpty() && !m.flags.contains(r.flag))
		{
			m.flags.add(r.flag);
		}
		if (r.flagged("pool"))
		{
			m.pool = r.attr("pool");
		}
		if (r.flagged("hidden"))
		{
			m.hidden = true;
		}
		if (r.flagged("group"))
		{
			m.group = r.attr("group");
		}
		if (r.flagged("noany"))
		{
			m.noany = true;
		}
		if (r.seq != null)
		{
			m.seq = new SeqInfo(r.seq.slot, r.seq.n, slotLen.get(r.seq.slot));
		}
		if (r.flagged("pity"))
		{
			m.pity = (int) Expr.parseFloat(r.attr("pity"));
		}
		if (r.flagged("note"))
		{
			m.note = r.attr("note");
		}
		if (!m.where.contains(where))
		{
			m.where.add(where);
		}
	}
}
