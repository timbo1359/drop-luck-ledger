package com.dropluckledger.ledger;

import com.dropluckledger.engine.Data;
import com.dropluckledger.engine.Data.Boss;
import com.dropluckledger.engine.Data.Param;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes the ledger as JSON.  The format is the web app's backup format ("drop-luck-ledger", version 1) with a few
 * extra plugin-only fields that the web app ignores, so a backup can be moved either way.
 */
public final class LedgerCodec
{
	public static final String APP = "drop-luck-ledger";

	private LedgerCodec()
	{
	}

	/**
	 * Thrown for text that is not a ledger backup.
	 */
	public static final class FormatException extends Exception
	{
		private static final long serialVersionUID = 1L;

		public FormatException(String msg)
		{
			super(msg);
		}
	}

	// ------------------------------------------------------------------ writing

	public static String toJson(Gson gson, Ledger ledger)
	{
		return gson.toJson(toTree(ledger));
	}

	static JsonObject toTree(Ledger ledger)
	{
		JsonObject root = new JsonObject();
		root.addProperty("app", APP);
		root.addProperty("v", 1);
		root.addProperty("saved", Instant.ofEpochMilli(ledger.now()).toString());
		synchronized (ledger)
		{
			Prefs p = ledger.rawPrefs();
			JsonObject po = new JsonObject();
			po.addProperty("window", p.window);
			po.addProperty("rowi", p.rowi);
			po.addProperty("lq", p.lq);
			po.addProperty("theme", p.theme);
			po.addProperty("scope", p.scope);
			po.addProperty("sel", p.sel);
			po.addProperty("tab", p.tab);
			po.addProperty("images", p.images);
			if (!p.hiddenBosses.isEmpty())
			{
				po.add("hiddenBosses", strings(p.hiddenBosses));
			}
			root.add("prefs", po);
			JsonObject bo = new JsonObject();
			for (Map.Entry<String, BossState> e : ledger.rawBosses().entrySet())
			{
				bo.add(e.getKey(), bossTree(e.getValue()));
			}
			for (Map.Entry<String, String> e : ledger.rawRetired().entrySet())
			{
				if (!bo.has(e.getKey()))
				{
					bo.add(e.getKey(), new JsonParser().parse(e.getValue()));
				}
			}
			root.add("bosses", bo);
			HistoryStage h = ledger.rawHistory();
			if (!h.isEmpty())
			{
				JsonObject ho = new JsonObject();
				JsonObject kc = new JsonObject();
				for (Map.Entry<String, Integer> e : h.kc.entrySet())
				{
					kc.addProperty(e.getKey(), e.getValue());
				}
				ho.add("kc", kc);
				JsonObject log = new JsonObject();
				for (Map.Entry<String, Map<String, Integer>> e : h.log.entrySet())
				{
					JsonObject page = new JsonObject();
					for (Map.Entry<String, Integer> it : e.getValue().entrySet())
					{
						page.addProperty(it.getKey(), it.getValue());
					}
					log.add(e.getKey(), page);
				}
				ho.add("log", log);
				ho.addProperty("kcTime", h.kcTime);
				ho.addProperty("logTime", h.logTime);
				root.add("history", ho);
			}
		}
		return root;
	}

	static JsonObject bossTree(BossState b)
	{
		JsonObject o = new JsonObject();
		o.addProperty("kc", b.kc);
		JsonArray segs = new JsonArray();
		for (Period p : b.segs)
		{
			JsonObject s = new JsonObject();
			s.addProperty("from", p.from);
			JsonObject params = new JsonObject();
			for (Map.Entry<String, Object> e : p.params.entrySet())
			{
				Object v = e.getValue();
				if (v instanceof Boolean)
				{
					params.addProperty(e.getKey(), (Boolean) v ? 1 : 0);
				}
				else if (v instanceof Number)
				{
					params.addProperty(e.getKey(), (Number) v);
				}
				else if (v != null)
				{
					params.addProperty(e.getKey(), v.toString());
				}
			}
			s.add("params", params);
			segs.add(s);
		}
		o.add("segs", segs);
		JsonArray drops = new JsonArray();
		for (Drop d : b.drops)
		{
			JsonObject j = new JsonObject();
			j.addProperty("id", d.id);
			j.addProperty("item", d.item);
			if (d.kc != null)
			{
				j.addProperty("kc", d.kc);
			}
			j.addProperty("qty", d.qty);
			j.addProperty("t", d.t);
			if (d.snap != null && !d.snap.isEmpty())
			{
				JsonObject sn = new JsonObject();
				for (Map.Entry<String, Integer> e : d.snap.entrySet())
				{
					sn.addProperty(e.getKey(), e.getValue());
				}
				j.add("snap", sn);
			}
			if (d.src != null)
			{
				j.addProperty("src", d.src);
			}
			drops.add(j);
		}
		o.add("drops", drops);
		o.add("track", strings(b.track));
		o.add("hide", strings(b.hide));
		o.addProperty("u", b.u);
		if (b.labelKc != null && !b.labelKc.isEmpty())
		{
			JsonObject lk = new JsonObject();
			for (Map.Entry<String, Integer> e : b.labelKc.entrySet())
			{
				lk.addProperty(e.getKey(), e.getValue());
			}
			o.add("labelKc", lk);
		}
		if (b.prov != null && b.prov > 0)
		{
			o.addProperty("prov", b.prov);
		}
		if (b.provDrops != null && !b.provDrops.isEmpty())
		{
			o.add("provDrops", strings(b.provDrops));
		}
		if (b.liveFrom != null)
		{
			o.addProperty("liveFrom", b.liveFrom);
		}
		if ((b.seen != null && !b.seen.isEmpty()) || b.liveFrom != null)
		{
			// written even when empty once the plugin counts kills itself, so an older ledger is told apart from one that read nothing
			JsonObject sn = new JsonObject();
			if (b.seen != null)
			{
				for (Map.Entry<String, Integer> e : b.seen.entrySet())
				{
					sn.addProperty(e.getKey(), e.getValue());
				}
			}
			o.add("seen", sn);
		}
		return o;
	}

	private static JsonArray strings(List<String> l)
	{
		JsonArray a = new JsonArray();
		for (String s : l)
		{
			a.add(s);
		}
		return a;
	}

	// ------------------------------------------------------------------ reading

	/**
	 * Reads a backup.  Bosses the plugin leaves out are kept as they are (written back, never shown), unknown settings are
	 * dropped, damaged entries are repaired or skipped.
	 */
	public static Ledger fromJson(Data data, String json) throws FormatException
	{
		return fromJson(data, json, null);
	}

	public static Ledger fromJson(Data data, String json, java.util.function.LongSupplier clock) throws FormatException
	{
		JsonObject root;
		try
		{
			JsonElement e = new JsonParser().parse(json == null ? "" : json.trim());
			if (!e.isJsonObject())
			{
				throw new FormatException("This is not a Drop Luck Ledger backup.");
			}
			root = e.getAsJsonObject();
		}
		catch (com.google.gson.JsonParseException | IllegalStateException ex)
		{
			throw new FormatException("This is not a Drop Luck Ledger backup (the file could not be read).");
		}
		if (!root.has("app") || !root.get("app").isJsonPrimitive() || !APP.equals(root.get("app").getAsString()))
		{
			throw new FormatException("This is not a Drop Luck Ledger backup.");
		}
		Ledger l = clock == null ? new Ledger(data) : new Ledger(data, clock);
		Prefs prefs = new Prefs();
		if (root.has("prefs") && root.get("prefs").isJsonObject())
		{
			JsonObject po = root.getAsJsonObject("prefs");
			prefs.window = clamp(po.get("window"), 1, 5000, prefs.window);
			prefs.rowi = flag(po.get("rowi"), 0);
			prefs.lq = flag(po.get("lq"), 1);
			prefs.images = flag(po.get("images"), 1);
			prefs.theme = text(po.get("theme"), "");
			prefs.scope = "new".equals(text(po.get("scope"), "all")) ? "new" : "all";
			String sel = text(po.get("sel"), "");
			prefs.sel = data.boss(sel) != null ? sel : "";
			prefs.tab = text(po.get("tab"), "tracker");
			for (String id : stringList(po.get("hiddenBosses")))
			{
				if (data.boss(id) != null && !prefs.hiddenBosses.contains(id))
				{
					prefs.hiddenBosses.add(id);
				}
			}
		}
		l.putPrefs(prefs);
		if (root.has("bosses") && root.get("bosses").isJsonObject())
		{
			for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("bosses").entrySet())
			{
				if (!e.getValue().isJsonObject())
				{
					continue;
				}
				Boss boss = data.boss(e.getKey());
				if (boss == null)
				{
					// a boss the plugin leaves out (the raids, for one, which the web app has): kept as it is and written back
					l.putRetired(e.getKey(), e.getValue().toString());
					continue;
				}
				l.putBoss(e.getKey(), sanitizeBoss(l, boss, e.getValue().getAsJsonObject()));
			}
		}
		HistoryStage hs = new HistoryStage();
		if (root.has("history") && root.get("history").isJsonObject())
		{
			JsonObject ho = root.getAsJsonObject("history");
			if (ho.has("kc") && ho.get("kc").isJsonObject())
			{
				for (Map.Entry<String, JsonElement> e : ho.getAsJsonObject("kc").entrySet())
				{
					int v = clamp(e.getValue(), 0, 100_000_000, 0);
					if (v > 0)
					{
						hs.kc.put(e.getKey(), v);
					}
				}
			}
			if (ho.has("log") && ho.get("log").isJsonObject())
			{
				for (Map.Entry<String, JsonElement> e : ho.getAsJsonObject("log").entrySet())
				{
					if (!e.getValue().isJsonObject())
					{
						continue;
					}
					Map<String, Integer> page = new LinkedHashMap<>();
					for (Map.Entry<String, JsonElement> it : e.getValue().getAsJsonObject().entrySet())
					{
						int v = clamp(it.getValue(), 0, 1_000_000, 0);
						if (v > 0)
						{
							page.put(it.getKey(), v);
						}
					}
					hs.log.put(e.getKey(), page);
				}
			}
			hs.kcTime = lng(ho.get("kcTime"), 0);
			hs.logTime = lng(ho.get("logTime"), 0);
		}
		l.putHistory(hs);
		return l;
	}

	private static BossState sanitizeBoss(Ledger l, Boss boss, JsonObject x)
	{
		BossState b = new BossState();
		b.kc = clamp(x.get("kc"), 0, 100_000_000, 0);
		b.u = lng(x.get("u"), l.now());
		List<Period> segs = new ArrayList<>();
		if (x.has("segs") && x.get("segs").isJsonArray())
		{
			for (JsonElement se : x.getAsJsonArray("segs"))
			{
				if (!se.isJsonObject())
				{
					continue;
				}
				JsonObject so = se.getAsJsonObject();
				Period p = new Period();
				p.from = Math.max(1, clamp(so.get("from"), 1, 1_000_000_000, 1));
				if (so.has("params") && so.get("params").isJsonObject())
				{
					for (Map.Entry<String, JsonElement> pe : so.getAsJsonObject("params").entrySet())
					{
						Param def = null;
						for (Param pd : boss.params)
						{
							if (pd.name.equals(pe.getKey()))
							{
								def = pd;
							}
						}
						if (def != null)
						{
							Object v = coerce(def, pe.getValue());
							if (v != null)
							{
								p.params.put(pe.getKey(), v);
							}
						}
					}
				}
				segs.add(p);
			}
		}
		if (!segs.isEmpty())
		{
			segs.sort(Comparator.comparingInt(p -> p.from));
			if (segs.get(0).from != 1)
			{
				segs.add(0, new Period(1, new LinkedHashMap<>()));
			}
			b.segs = segs;
		}
		if (x.has("drops") && x.get("drops").isJsonArray())
		{
			for (JsonElement de : x.getAsJsonArray("drops"))
			{
				if (!de.isJsonObject())
				{
					continue;
				}
				JsonObject d = de.getAsJsonObject();
				if (!d.has("item") || !d.get("item").isJsonPrimitive() || !d.get("item").getAsJsonPrimitive().isString())
				{
					continue;
				}
				Drop r = new Drop();
				r.id = text(d.get("id"), "");
				if (r.id.isEmpty())
				{
					r.id = l.newId();
				}
				r.item = d.get("item").getAsString();
				r.kc = d.has("kc") && !d.get("kc").isJsonNull() ? Integer.valueOf(clamp(d.get("kc"), 1, 1_000_000_000, 1)) : null;
				r.qty = clamp(d.get("qty"), 1, 1_000_000_000, 1);
				r.t = lng(d.get("t"), 0);
				if (d.has("snap") && d.get("snap").isJsonObject())
				{
					Map<String, Integer> sn = new LinkedHashMap<>();
					for (Map.Entry<String, JsonElement> se : d.getAsJsonObject("snap").entrySet())
					{
						sn.put(se.getKey(), clamp(se.getValue(), 0, 1_000_000_000, 0));
					}
					r.snap = sn.isEmpty() ? null : sn;
				}
				String src = text(d.get("src"), "");
				r.src = Drop.SRC_IMPORT.equals(src) || Drop.SRC_LIVE.equals(src) || Drop.SRC_MANUAL.equals(src) ? src : null;
				b.drops.add(r);
			}
		}
		b.track = stringList(x.get("track"));
		b.hide = stringList(x.get("hide"));
		if (x.has("labelKc") && x.get("labelKc").isJsonObject())
		{
			Map<String, Integer> lk = new LinkedHashMap<>();
			for (Map.Entry<String, JsonElement> le : x.getAsJsonObject("labelKc").entrySet())
			{
				lk.put(le.getKey(), clamp(le.getValue(), 0, 100_000_000, 0));
			}
			b.labelKc = lk.isEmpty() ? null : lk;
		}
		int prov = clamp(x.get("prov"), 0, 1_000_000, 0);
		b.prov = prov > 0 ? prov : null;
		List<String> pd = stringList(x.get("provDrops"));
		b.provDrops = pd.isEmpty() ? null : pd;
		int live = clamp(x.get("liveFrom"), 0, 100_000_000, 0);
		b.liveFrom = live > 0 ? live : null;
		if (x.has("seen") && x.get("seen").isJsonObject())
		{
			Map<String, Integer> sn = new LinkedHashMap<>();
			for (Map.Entry<String, JsonElement> se : x.getAsJsonObject("seen").entrySet())
			{
				int k = clamp(se.getValue(), 0, 100_000_000, 0);
				if (k > 0 && boss.reads.contains(se.getKey()))
				{
					sn.put(se.getKey(), k);
				}
			}
			b.seen = sn.isEmpty() ? null : sn;
		}
		else if (b.liveFrom != null && !boss.reads.isEmpty())
		{
			readSinceLiveFrom(b, boss);
		}
		b.kc = Math.max(b.kc, b.maxDropKc());
		return b;
	}

	/**
	 * A ledger from before the plugin remembered which kills it read each setting for: it read them from the first kill it counted.
	 * The settings the boss infers then give the earlier kills their first value read, as they would have in a new ledger.
	 */
	private static void readSinceLiveFrom(BossState b, Boss boss)
	{
		Map<String, Object> defaults = com.dropluckledger.engine.Engine.defaultParams(boss);
		b.seen = new LinkedHashMap<>();
		for (String r : boss.reads)
		{
			b.seen.put(r, b.liveFrom);
		}
		for (String r : boss.infer)
		{
			if (b.liveFrom <= 1 || Ledger.setByPlayer(b, r, b.liveFrom))
			{
				continue;
			}
			Object first = null;
			for (Period p : b.segs)
			{
				if (p.from >= b.liveFrom && p.params.containsKey(r))
				{
					first = p.params.get(r);
					break;
				}
			}
			if (first != null)
			{
				Ledger.setBeforeLocked(b, r, first, b.liveFrom, defaults);
			}
		}
	}

	/**
	 * A setting value in the form the maths takes: 1 or 0 for yes/no, an integer, or the chosen option.
	 */
	private static Object coerce(Param p, JsonElement v)
	{
		if (v == null || v.isJsonNull() || !v.isJsonPrimitive())
		{
			return null;
		}
		JsonPrimitive pr = v.getAsJsonPrimitive();
		if ("bool".equals(p.type))
		{
			if (pr.isBoolean())
			{
				return pr.getAsBoolean() ? 1 : 0;
			}
			return flag(pr, 0);
		}
		if ("int".equals(p.type))
		{
			double d = number(pr, Double.NaN);
			if (Double.isNaN(d))
			{
				return null;
			}
			long r = Math.round(d);
			if (p.min != null)
			{
				r = Math.max(r, Math.round(p.min));
			}
			if (p.max != null)
			{
				r = Math.min(r, Math.round(p.max));
			}
			return (int) r;
		}
		String s = pr.getAsString();
		return p.options != null && p.options.contains(s) ? s : null;
	}

	private static List<String> stringList(JsonElement e)
	{
		List<String> out = new ArrayList<>();
		if (e != null && e.isJsonArray())
		{
			for (JsonElement s : e.getAsJsonArray())
			{
				if (s.isJsonPrimitive() && s.getAsJsonPrimitive().isString())
				{
					out.add(s.getAsString());
				}
			}
		}
		return out;
	}

	private static String text(JsonElement e, String def)
	{
		return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
	}

	private static double number(JsonElement e, double def)
	{
		if (e == null || !e.isJsonPrimitive())
		{
			return def;
		}
		JsonPrimitive p = e.getAsJsonPrimitive();
		if (p.isNumber())
		{
			return p.getAsDouble();
		}
		if (p.isBoolean())
		{
			return p.getAsBoolean() ? 1 : 0;
		}
		try
		{
			return Double.parseDouble(p.getAsString().trim());
		}
		catch (NumberFormatException ex)
		{
			return def;
		}
	}

	private static int clamp(JsonElement e, int lo, int hi, int def)
	{
		double d = number(e, Double.NaN);
		if (Double.isNaN(d) || Double.isInfinite(d))
		{
			return def;
		}
		long r = Math.round(d);
		return (int) Math.max(lo, Math.min(hi, r));
	}

	private static long lng(JsonElement e, long def)
	{
		double d = number(e, Double.NaN);
		return Double.isNaN(d) || Double.isInfinite(d) ? def : (long) d;
	}

	/**
	 * 1 for true / 1 / "1" / "true", 0 for false / 0, the default for anything else.
	 */
	private static int flag(JsonElement e, int def)
	{
		if (e == null || !e.isJsonPrimitive())
		{
			return def;
		}
		JsonPrimitive p = e.getAsJsonPrimitive();
		if (p.isBoolean())
		{
			return p.getAsBoolean() ? 1 : 0;
		}
		if (p.isNumber())
		{
			return p.getAsDouble() != 0 ? 1 : 0;
		}
		String s = p.getAsString().trim();
		if (s.equalsIgnoreCase("true") || s.equals("1"))
		{
			return 1;
		}
		if (s.equalsIgnoreCase("false") || s.equals("0") || s.isEmpty())
		{
			return 0;
		}
		return def;
	}
}
