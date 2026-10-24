package com.dropluckledger.engine;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The boss drop data (boss.json compiled from the .ds sources) and the compiled form of its expressions.
 * Fields without "transient" are read straight from the JSON; the transient ones are filled in by {@link #prepare()}.
 */
public final class Data
{
	public List<Boss> bosses = new ArrayList<>();
	public List<Shared> shared = new ArrayList<>();

	public transient Map<String, Boss> boss = new LinkedHashMap<>();
	public transient Map<String, Shared> sharedById = new HashMap<>();

	/**
	 * Reads and prepares the data from a stream (UTF-8 JSON).
	 */
	public static Data load(Gson gson, InputStream in) throws IOException
	{
		try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8))
		{
			Data d = gson.fromJson(r, Data.class);
			if (d == null)
			{
				throw new IOException("empty boss data");
			}
			d.prepare();
			return d;
		}
	}

	public Boss boss(String id)
	{
		return boss.get(id);
	}

	/**
	 * Compiles every expression once.
	 */
	public void prepare()
	{
		boss = new LinkedHashMap<>();
		sharedById = new HashMap<>();
		if (bosses == null)
		{
			bosses = new ArrayList<>();
		}
		if (shared == null)
		{
			shared = new ArrayList<>();
		}
		for (Shared s : shared)
		{
			for (Table t : s.tables)
			{
				t.prepare();
			}
			sharedById.put(s.id, s);
		}
		for (Boss b : bosses)
		{
			b.data = this;
			b.prepare();
			boss.put(b.id, b);
		}
	}

	// ------------------------------------------------------------------ model classes

	public static final class Shared
	{
		public String id;
		public String name;
		public List<Table> tables = new ArrayList<>();
	}

	public static final class Param
	{
		public String name;
		public String type; // bool | int | select
		public String label;
		@SerializedName("default")
		public Object def;
		public Double min;
		public Double max;
		public List<String> options;
	}

	public static final class LogItem
	{
		public String name;
		public boolean found;
	}

	public static final class Derive
	{
		public String name;
		public String expr;
	}

	public static final class Derived
	{
		public final String name;
		public final Expr f;

		Derived(String name, Expr f)
		{
			this.name = name;
			this.f = f;
		}
	}

	public static final class RollVar
	{
		public final String name;
		public final double start;

		RollVar(String name, double start)
		{
			this.name = name;
			this.start = start;
		}
	}

	public static final class Boss
	{
		public String id;
		public String name;
		public String cat;
		public String wiki;
		public List<String> kc = new ArrayList<>();
		public String samples;
		public String rolls;
		public List<String> notes = new ArrayList<>();
		public List<String> always = new ArrayList<>();
		public List<Param> params = new ArrayList<>();
		public List<Table> tables = new ArrayList<>();
		public List<Row> tert = new ArrayList<>();
		public List<Row> pets = new ArrayList<>();
		public List<Derive> derive = new ArrayList<>();
		public List<LogItem> logItems = new ArrayList<>();
		public String rollvar;
		public String simnote;
		/**
		 * The share of kills that pay out (an expression of the settings), or null: see {@link Engine#lootScale}.
		 */
		public String scale;
		/**
		 * How loot events relate to kills: "kill" (one loot event per kill, the default), "extra" (rewards taken later that never
		 * count as kills: Tempoross), "claim" (every loot event is one run and there is no kill-count message: Doom of
		 * Mokhaiotl) or "top" (only the top damage dealer gets the loot: a kill without loot paid another player).
		 */
		public String lootmode;
		/**
		 * Names of the other monsters whose loot counts for this boss (the God Wars bodyguards); it is recorded under the boss but
		 * never counts as a kill.
		 */
		public List<String> extraLoot = new ArrayList<>();
		/**
		 * The per-kill setting that counts the loots of those other monsters (EXTRACOUNT): each bodyguard loot the plugin sees adds one to
		 * the "Bodyguards killed with each kill" of the kill it belongs to.  Null for most bosses.
		 */
		public String extraCount;
		/**
		 * Settings a kill has when its regular loot arrives (ONLOOT) and when it never does (NOLOOT): another player got it, the
		 * corpse was destroyed, the loot was sacrificed.  Empty for most bosses.
		 */
		public Map<String, String> onLoot = new LinkedHashMap<>();
		public Map<String, String> noLoot = new LinkedHashMap<>();
		/**
		 * Settings the plugin reads from the game for every kill it sees (READS): for those kills they are not the player's to set,
		 * so what the player sets applies to the other kills only.
		 */
		public List<String> reads = new ArrayList<>();
		/**
		 * Of those, the ones whose first value seen also stands for the earlier kills, unless the player set them (INFER): the
		 * Fishing level of the first Tempoross reward seen is a better guess for the rewards before than a default.
		 */
		public List<String> infer = new ArrayList<>();

		public transient Data data;
		public transient Expr rollsFn;
		public transient Expr scaleFn;
		public transient RollVar rv;
		public transient List<Derived> derived = new ArrayList<>();
		// lazily computed, shared between threads
		transient Luck.Dep depCache;
		transient Map<String, Engine.ItemMeta> metaCache;
		transient java.util.Set<String> logCache;
		transient java.util.Set<String> tertCache;

		void prepare()
		{
			notes = nn(notes);
			always = nn(always);
			params = nn(params);
			tables = nn(tables);
			tert = nn(tert);
			pets = nn(pets);
			derive = nn(derive);
			kc = nn(kc);
			logItems = nn(logItems);
			rollsFn = rolls != null && !rolls.isEmpty() ? Expr.parse(rolls) : null;
			scaleFn = scale != null && !scale.isEmpty() ? Expr.parse(scale) : null;
			extraLoot = nn(extraLoot);
			reads = nn(reads);
			infer = nn(infer);
			onLoot = onLoot == null ? new LinkedHashMap<>() : onLoot;
			noLoot = noLoot == null ? new LinkedHashMap<>() : noLoot;
			if (lootmode == null || lootmode.isEmpty())
			{
				lootmode = "kill";
			}
			rv = null;
			if (rollvar != null && !rollvar.isEmpty())
			{
				String[] p = rollvar.trim().split("\\s+");
				rv = new RollVar(p[0], p.length > 1 ? Double.parseDouble(p[1]) : 0);
			}
			derived = new ArrayList<>();
			for (Derive d : derive)
			{
				derived.add(new Derived(d.name, Expr.parse(d.expr)));
			}
			for (Table t : tables)
			{
				t.prepare();
			}
			for (Row r : tert)
			{
				r.prepare();
			}
			for (Row r : pets)
			{
				r.prepare();
			}
			// the rows of each no-duplicate group, for the expressions that read how much of the whole set is owned (setowned)
			Map<String, List<Row>> groups = new LinkedHashMap<>();
			for (Row r : allRows())
			{
				String g = r.attrs.get("nodup");
				if (g != null && !g.isEmpty())
				{
					groups.computeIfAbsent(g, k -> new ArrayList<>()).add(r);
				}
			}
			for (List<Row> g : groups.values())
			{
				for (Row r : g)
				{
					r.group = g;
				}
			}
		}

		public boolean lootIsExtra()
		{
			return "extra".equals(lootmode);
		}

		public boolean lootIsClaim()
		{
			return "claim".equals(lootmode);
		}

		public boolean lootToTop()
		{
			return "top".equals(lootmode);
		}

		/**
		 * True when a kill without regular loot says something about the kill (see {@link #noLoot}).
		 */
		public boolean decidesOnLoot()
		{
			return !noLoot.isEmpty();
		}

		/**
		 * Settings from {@link #onLoot} or {@link #noLoot}, with numbers as numbers.
		 */
		public static Map<String, Object> settings(Map<String, String> m)
		{
			Map<String, Object> out = new LinkedHashMap<>();
			for (Map.Entry<String, String> e : m.entrySet())
			{
				String v = e.getValue();
				try
				{
					out.put(e.getKey(), Integer.parseInt(v));
				}
				catch (NumberFormatException ex)
				{
					out.put(e.getKey(), v);
				}
			}
			return out;
		}

		/**
		 * Items only the tertiary and pet rolls give (clue scrolls, pets): loot of nothing else is no regular loot.
		 */
		public synchronized java.util.Set<String> tertiaryOnly()
		{
			if (tertCache == null)
			{
				java.util.Set<String> tableItems = new java.util.HashSet<>();
				for (Table t : tables)
				{
					for (Row r : t.items)
					{
						tableItems.add(r.name);
					}
					for (Sub sb : t.subs)
					{
						for (Row r : sb.items)
						{
							tableItems.add(r.name);
						}
					}
				}
				java.util.Set<String> s = new java.util.HashSet<>();
				for (Row r : tert)
				{
					if (!tableItems.contains(r.name))
					{
						s.add(r.name);
					}
				}
				for (Row r : pets)
				{
					if (!tableItems.contains(r.name))
					{
						s.add(r.name);
					}
				}
				tertCache = s;
			}
			return tertCache;
		}

		/**
		 * Every row of every table, sub-table, tertiary and pet row (not the shared tables reached through gates).
		 */
		public List<Row> allRows()
		{
			List<Row> out = new ArrayList<>();
			for (Table t : tables)
			{
				out.addAll(t.items);
				for (Sub s : t.subs)
				{
					out.addAll(s.items);
				}
			}
			out.addAll(tert);
			out.addAll(pets);
			return out;
		}

		/**
		 * Names of the collection-log items of this boss (rows flagged log in the data).
		 */
		public synchronized java.util.Set<String> logSet()
		{
			if (logCache == null)
			{
				java.util.Set<String> s = new java.util.LinkedHashSet<>();
				for (Row r : allRows())
				{
					if (r.log)
					{
						s.add(r.name);
					}
				}
				logCache = s;
			}
			return logCache;
		}

		public synchronized Luck.Dep dep()
		{
			if (depCache == null)
			{
				depCache = Luck.scanBoss(this);
			}
			return depCache;
		}

		public synchronized Map<String, Engine.ItemMeta> meta()
		{
			if (metaCache == null)
			{
				metaCache = Engine.itemMeta(this);
			}
			return metaCache;
		}
	}

	public static final class Table
	{
		public String id;
		public String label;
		public boolean pre;
		public boolean par;
		public String alt;
		public List<Row> items = new ArrayList<>();
		public List<Gate> gates = new ArrayList<>();
		public List<Sub> subs = new ArrayList<>();
		public Map<String, String> opts = new HashMap<>();

		public transient Expr ifFn;
		public transient Expr gateFn;
		public transient Expr multFn;
		public transient Expr rollsFn;
		public transient Double cancels;
		public transient Double abs;
		/**
		 * A follow table's share for the boss's pet (petslot=a/b), 0 for none: once the pet is owned the slot is gone and the other
		 * rows share it out (the Abyssal Sire's Font of Consumption is out of 123 instead of 128 once the orphan is owned).
		 */
		public transient double petslot;
		/**
		 * The rolls never give the same row twice (distinct=1, the Chambers of Xeric's standard loot).
		 */
		public transient boolean distinct;
		/**
		 * Rolled whoever got the kill's own loot: the boss's loot share (SCALE) does not apply (noscale=1, the God Wars bodyguards).
		 */
		public transient boolean noscale;

		void prepare()
		{
			items = nn(items);
			gates = nn(gates);
			subs = nn(subs);
			if (opts == null)
			{
				opts = new HashMap<>();
			}
			ifFn = opts.containsKey("if") && !opts.get("if").isEmpty() ? Expr.cond(opts.get("if")) : null;
			gateFn = has(opts, "gate") ? Expr.parse(opts.get("gate")) : null;
			multFn = has(opts, "mult") ? Expr.parse(opts.get("mult")) : null;
			rollsFn = opts.containsKey("rolls") ? Expr.parse(opts.get("rolls")) : null;
			cancels = Row.parseCancels(opts.get("cancels"));
			abs = has(opts, "abs") ? Expr.rate(opts.get("abs")).eval(new Env()) : null;
			petslot = has(opts, "petslot") ? Expr.rate(opts.get("petslot")).eval(new Env()) : 0;
			distinct = has(opts, "distinct");
			noscale = has(opts, "noscale");
			for (Row r : items)
			{
				r.prepare();
			}
			for (Sub s : subs)
			{
				s.prepare();
			}
		}

		public String opt(String k)
		{
			return opts.get(k);
		}
	}

	public static final class Gate
	{
		public String table;
		public String rate;
		public double p;
		public Map<String, String> opts = new HashMap<>();

		public boolean opt(String k)
		{
			return opts != null && has(opts, k);
		}
	}

	public static final class Sub
	{
		public String id;
		public double p;
		public List<Row> items = new ArrayList<>();
		public Map<String, String> opts = new HashMap<>();

		public transient Expr ifFn;

		void prepare()
		{
			items = nn(items);
			if (opts == null)
			{
				opts = new HashMap<>();
			}
			ifFn = has(opts, "if") ? Expr.cond(opts.get("if")) : null;
			for (Row r : items)
			{
				r.prepare();
			}
		}
	}

	public static final class VarClause
	{
		public final List<String> conds;
		public final Expr rate;

		VarClause(List<String> conds, Expr rate)
		{
			this.conds = conds;
			this.rate = rate;
		}
	}

	public static final class Seq
	{
		public final String slot;
		public final int n;
		public final int[] ns;
		public final int last;

		Seq(String slot, int[] ns)
		{
			this.slot = slot;
			this.ns = ns;
			this.n = ns[0];
			int m = ns[0];
			for (int x : ns)
			{
				m = Math.max(m, x);
			}
			this.last = m;
		}
	}

	public static final class Row
	{
		public String flag = "";
		public String name;
		public String qty;
		public String rate;
		public Double p;
		public Map<String, String> attrs = new HashMap<>();
		public String key;
		public boolean log;

		public transient Expr ifFn;
		public transient Expr expr;
		public transient List<VarClause> var;
		public transient Double cancels;
		public transient Seq seq;
		public transient double pity;
		/**
		 * The rows of the row's no-duplicate group (this one included), or null when it is in none: what {@code setowned} reads.
		 */
		public transient List<Row> group;

		private static final Pattern SEQ = Pattern.compile("^([^:]+):(\\d+(?:,\\d+)*)$");

		/**
		 * True when the drop rate is not published yet ("unknown"): the row is shown and tracked, but never rolled.
		 */
		public boolean unknownRate()
		{
			return rate != null && "unknown".equalsIgnoreCase(rate.trim());
		}

		void prepare()
		{
			if (attrs == null)
			{
				attrs = new HashMap<>();
			}
			if (flag == null)
			{
				flag = "";
			}
			ifFn = has(attrs, "if") ? Expr.cond(attrs.get("if")) : null;
			expr = has(attrs, "expr") ? Expr.parse(attrs.get("expr")) : null;
			var = has(attrs, "var") ? parseVar(attrs.get("var")) : null;
			cancels = parseCancels(attrs.get("cancels"));
			seq = null;
			if (has(attrs, "seq"))
			{
				Matcher m = SEQ.matcher(attrs.get("seq"));
				if (!m.matches())
				{
					throw new IllegalArgumentException("bad seq=" + attrs.get("seq"));
				}
				String[] parts = m.group(2).split(",");
				int[] ns = new int[parts.length];
				for (int i = 0; i < parts.length; i++)
				{
					ns[i] = Integer.parseInt(parts[i]);
				}
				seq = new Seq(m.group(1), ns);
			}
			pity = has(attrs, "pity") ? Expr.parseFloat(attrs.get("pity")) : 0;
		}

		public String attr(String k)
		{
			return attrs.get(k);
		}

		/**
		 * JavaScript truthiness of an attribute string: set and non-empty.
		 */
		public boolean flagged(String k)
		{
			return has(attrs, k);
		}

		static Double parseCancels(String v)
		{
			if (v == null || v.isEmpty())
			{
				return null;
			}
			if (v.equals("main") || v.equals("all"))
			{
				return Double.POSITIVE_INFINITY;
			}
			if (v.equals("main-roll"))
			{
				return 1.0;
			}
			Matcher m = Pattern.compile("^main:(\\d+)$").matcher(v);
			if (m.matches())
			{
				return Double.parseDouble(m.group(1));
			}
			throw new IllegalArgumentException("bad cancels=" + v);
		}

		private static List<VarClause> parseVar(String src)
		{
			List<VarClause> out = new ArrayList<>();
			for (String e : Expr.splitTop(src, ','))
			{
				int i = e.indexOf(':');
				List<String> conds = new ArrayList<>();
				for (String c : e.substring(0, i).trim().split("\\+"))
				{
					String t = c.trim();
					if (!t.isEmpty())
					{
						conds.add(t);
					}
				}
				out.add(new VarClause(conds, Expr.rate(e.substring(i + 1))));
			}
			return out;
		}
	}

	// ------------------------------------------------------------------ helpers
	private static boolean has(Map<String, String> m, String k)
	{
		String v = m == null ? null : m.get(k);
		return v != null && !v.isEmpty();
	}

	private static <T> List<T> nn(List<T> l)
	{
		return l == null ? new ArrayList<>() : l;
	}
}
