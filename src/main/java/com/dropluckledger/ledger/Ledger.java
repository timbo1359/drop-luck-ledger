package com.dropluckledger.ledger;

import com.dropluckledger.engine.Catalog;
import com.dropluckledger.engine.Data;
import com.dropluckledger.engine.Data.Boss;
import com.dropluckledger.engine.Engine.ItemMeta;
import com.dropluckledger.engine.Luck;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * The player's kill counts, drops and boss settings.  All access goes through this class, which is safe to use from any
 * thread: every change happens under one lock, and readers get copies.
 */
public final class Ledger
{
	/**
	 * Told after every change.  Called on the thread that made the change, outside the lock; keep it quick.
	 */
	public interface Listener
	{
		/**
		 * @param bossId the boss that changed, or null when the change is not about one boss (settings, restore)
		 */
		void changed(String bossId);
	}

	private final Data data;
	private final LongSupplier clock;
	private final Map<String, BossState> bosses = new LinkedHashMap<>();
	/**
	 * Bosses in the ledger file that the plugin leaves out (the raids, for one), as the JSON they were read from.  They are
	 * never shown or counted, only written back when the ledger is saved or backed up, so their kills and drops are not lost.
	 */
	private final Map<String, String> retired = new LinkedHashMap<>();
	private final Map<String, Long> revisions = new HashMap<>();
	private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
	private final Random rnd = new Random();
	private Prefs prefs = new Prefs();
	private long revision;

	public Ledger(Data data)
	{
		this(data, System::currentTimeMillis);
	}

	public Ledger(Data data, LongSupplier clock)
	{
		this.data = data;
		this.clock = clock;
	}

	public Data data()
	{
		return data;
	}

	public long now()
	{
		return clock.getAsLong();
	}

	public void addListener(Listener l)
	{
		listeners.add(l);
	}

	public void removeListener(Listener l)
	{
		listeners.remove(l);
	}

	private void fire(String bossId)
	{
		for (Listener l : listeners)
		{
			l.changed(bossId);
		}
	}

	private void touch(String id)
	{
		revision++;
		if (id != null)
		{
			revisions.merge(id, 1L, Long::sum);
			BossState b = bosses.get(id);
			if (b != null)
			{
				b.u = now();
			}
		}
	}

	/**
	 * Changes every time anything changes.
	 */
	public synchronized long revision()
	{
		return revision;
	}

	/**
	 * Changes every time the given boss changes (or the settings that affect all bosses).
	 */
	public synchronized long revision(String id)
	{
		return revisions.getOrDefault(id, 0L) + globalRev;
	}

	private long globalRev;

	// ------------------------------------------------------------------ reading

	public synchronized boolean hasData(String id)
	{
		BossState b = bosses.get(id);
		return b != null && (b.kc > 0 || !b.drops.isEmpty());
	}

	/**
	 * Ids of the bosses with a kill count or a drop.
	 */
	public synchronized List<String> activeBosses()
	{
		List<String> out = new ArrayList<>();
		for (Boss b : Catalog.ordered(data))
		{
			if (hasData(b.id))
			{
				out.add(b.id);
			}
		}
		return out;
	}

	/**
	 * A copy of the boss state (a blank one when the boss has none yet).
	 */
	public synchronized BossState copyOf(String id)
	{
		BossState b = bosses.get(id);
		return b == null ? new BossState() : b.copy();
	}

	public synchronized int kc(String id)
	{
		BossState b = bosses.get(id);
		return b == null ? 0 : b.kc;
	}

	/**
	 * How many copies of an item the boss has in the ledger.
	 */
	public synchronized int copies(String id, String item)
	{
		BossState b = bosses.get(id);
		return b == null ? 0 : b.copies(item);
	}

	/**
	 * The state in the form the luck maths takes.
	 */
	public synchronized Luck.BossRecord record(String id)
	{
		BossState s = bosses.get(id);
		Luck.BossRecord r = new Luck.BossRecord();
		if (s == null)
		{
			return r;
		}
		r.kc = s.kc;
		for (Period p : s.segs)
		{
			r.segs.add(new Luck.Seg(p.from, new LinkedHashMap<>(p.params)));
		}
		for (Drop d : s.drops)
		{
			r.drops.add(new Luck.DropRec(d.item, d.kc));
		}
		return r;
	}

	public synchronized Map<String, Double> globals()
	{
		Map<String, Double> g = new HashMap<>();
		g.put("rowi", prefs.rowi != 0 ? 1.0 : 0.0);
		g.put("lq", prefs.lq != 0 ? 1.0 : 0.0);
		return g;
	}

	public synchronized Prefs prefs()
	{
		return prefs.copy();
	}

	/**
	 * The settings in force for the next kill (the last period, over the boss defaults).
	 */
	public synchronized Map<String, Object> currentParams(String id)
	{
		Boss boss = data.boss(id);
		Map<String, Object> out = com.dropluckledger.engine.Engine.defaultParams(boss);
		BossState s = bosses.get(id);
		if (s != null)
		{
			out.putAll(s.lastPeriod().params);
		}
		return out;
	}

	/**
	 * Items shown for the boss: the default ones the player has not hidden, then any they added.
	 */
	public synchronized List<String> trackedNames(String id)
	{
		Boss boss = data.boss(id);
		if (boss == null)
		{
			return Collections.emptyList();
		}
		BossState s = bosses.get(id);
		Set<String> hide = s == null ? Collections.emptySet() : new LinkedHashSet<>(s.hide);
		List<String> out = new ArrayList<>();
		for (String n : Catalog.defaultTracked(boss))
		{
			if (!hide.contains(n))
			{
				out.add(n);
			}
		}
		if (s != null)
		{
			Map<String, ItemMeta> meta = boss.meta();
			for (String n : s.track)
			{
				ItemMeta im = meta.get(n);
				if (!out.contains(n) && im != null && !im.hidden)
				{
					out.add(n);
				}
			}
		}
		return out;
	}

	/**
	 * Changes one boss under the lock and tells the listeners.  The boss is created when it does not exist yet.
	 */
	public <T> T update(String id, Function<BossState, T> fn)
	{
		T out;
		synchronized (this)
		{
			BossState s = bosses.computeIfAbsent(id, k -> blank());
			out = fn.apply(s);
			touch(id);
		}
		fire(id);
		return out;
	}

	private BossState blank()
	{
		BossState b = new BossState();
		b.u = now();
		return b;
	}

	// ------------------------------------------------------------------ changes made by the player or by history

	/**
	 * The player sets the kill count by hand.  The game's counts seen so far are forgotten, so the next one the game shows (a kill-count
	 * message, a Collection Log page) is taken as it is rather than added on top of the player's number.
	 */
	public void setKc(String id, int kc)
	{
		update(id, s ->
		{
			s.kc = Math.max(s.maxDropKc(), Math.max(0, kc));
			s.prov = null;
			s.provDrops = null;
			s.labelKc = null;
			return null;
		});
	}

	/**
	 * The player adds or takes away kills by hand (as {@link #setKc}, the game's counts seen so far are forgotten).
	 */
	public void addKc(String id, int n)
	{
		update(id, s ->
		{
			s.kc = Math.max(s.maxDropKc(), Math.max(0, s.kc + n));
			s.labelKc = null;
			return null;
		});
	}

	/**
	 * Sets the kill count to the game's own count of every kill of the boss (the player agreed to it, from a Collection Log page or the
	 * Boss Kill Log), up or down, but never below the kill of the last drop with a kill number.  The game's counts become the ones last
	 * seen.
	 *
	 * @param labels kill-count label (as in the boss data) to the game's count for it
	 * @param total  the game's count of every kill
	 * @return true when the kill count changed
	 */
	public boolean kcToGame(String id, Map<String, Integer> labels, int total)
	{
		return update(id, s ->
		{
			int to = Math.max(s.maxDropKc(), Math.max(0, total));
			boolean changed = to != s.kc;
			s.kc = to;
			s.prov = null;
			s.provDrops = null;
			s.labels().clear();
			s.labels().putAll(labels);
			return changed;
		});
	}

	/**
	 * Logs a drop (the player's own entry, or one seen in game).
	 */
	public Drop logDrop(String id, String item, Integer kc, int qty, Map<String, Integer> snap, String src)
	{
		return update(id, s -> addDropLocked(id, s, item, kc, qty, snap, src));
	}

	/**
	 * Adds a drop to a boss the caller already holds through {@link #update}.
	 */
	public Drop addDropLocked(String id, BossState s, String item, Integer kc, int qty, Map<String, Integer> snap, String src)
	{
		Drop d = new Drop();
		d.id = newId();
		d.item = item;
		d.kc = kc == null ? null : Math.max(1, kc);
		d.qty = Math.max(1, qty);
		d.t = now();
		d.src = src;
		d.snap = snap == null || snap.isEmpty() ? null : new LinkedHashMap<>(snap);
		s.drops.add(d);
		if (d.kc != null && d.kc > s.kc)
		{
			s.kc = d.kc;
		}
		s.hide.remove(item);
		Boss boss = data.boss(id);
		if (boss != null && !Catalog.defaultTracked(boss).contains(item) && !s.track.contains(item))
		{
			s.track.add(item);
		}
		return d;
	}

	/**
	 * Changes a logged drop.  Returns false when there is no such drop.
	 */
	public boolean editDrop(String id, String dropId, String item, Integer kc, int qty, Map<String, Integer> snap)
	{
		return update(id, s ->
		{
			for (Drop d : s.drops)
			{
				if (d.id.equals(dropId))
				{
					d.item = item;
					d.kc = kc == null ? null : Math.max(1, kc);
					d.qty = Math.max(1, qty);
					d.snap = snap == null || snap.isEmpty() ? null : new LinkedHashMap<>(snap);
					if (d.src != null && !Drop.SRC_LIVE.equals(d.src))
					{
						d.src = Drop.SRC_MANUAL;
					}
					if (d.kc != null && d.kc > s.kc)
					{
						s.kc = d.kc;
					}
					s.hide.remove(item);
					Boss boss = data.boss(id);
					if (boss != null && !Catalog.defaultTracked(boss).contains(item) && !s.track.contains(item))
					{
						s.track.add(item);
					}
					return true;
				}
			}
			return false;
		});
	}

	public boolean deleteDrop(String id, String dropId)
	{
		return update(id, s ->
		{
			if (s.provDrops != null)
			{
				s.provDrops.remove(dropId);
			}
			return s.drops.removeIf(d -> d.id.equals(dropId));
		});
	}

	/**
	 * Changes a boss setting.  With newKillsOnly the change starts at the next kill; before that the earlier kills keep
	 * the old value (the web app calls this "new kills only").
	 */
	public void setParam(String id, String name, Object value, boolean newKillsOnly)
	{
		update(id, s ->
		{
			if (newKillsOnly && s.kc > 0)
			{
				int from = s.kc + 1;
				Period seg = null;
				for (Period p : s.segs)
				{
					if (p.from == from)
					{
						seg = p;
					}
				}
				if (seg == null)
				{
					Period prev = null;
					for (Period p : s.segs)
					{
						if (p.from < from)
						{
							prev = p;
						}
					}
					seg = new Period(from, prev == null ? null : prev.params);
					s.segs.add(seg);
					s.segs.sort((a, b) -> Integer.compare(a.from, b.from));
				}
				for (Period p : s.segs)
				{
					if (p.from >= from)
					{
						p.params.put(name, value);
					}
				}
			}
			else
			{
				for (Period p : s.segs)
				{
					p.params.put(name, value);
				}
			}
			return null;
		});
	}

	/**
	 * Sets a value for the kills the plugin never read this setting for (see {@link BossState#seen}): the kills before the first one
	 * it read it for, or every kill when it never has.  The kills it read keep what the game said about them.
	 */
	public void setParamBefore(String id, String name, Object value)
	{
		Map<String, Object> defaults = com.dropluckledger.engine.Engine.defaultParams(data.boss(id));
		update(id, s ->
		{
			Integer seen = s.seenFrom(name);
			setBeforeLocked(s, name, value, seen == null ? Integer.MAX_VALUE : seen, defaults);
			return null;
		});
	}

	/**
	 * Gives the kills before a kill number a value for one setting, leaving that kill and the later ones as they are.
	 */
	static void setBeforeLocked(BossState s, String name, Object value, int until, Map<String, Object> defaults)
	{
		s.segs.sort((a, b) -> Integer.compare(a.from, b.from));
		if (until != Integer.MAX_VALUE)
		{
			boolean starts = false;
			Period before = null;
			for (Period p : s.segs)
			{
				if (p.from == until)
				{
					starts = true;
				}
				if (p.from < until)
				{
					before = p;
				}
			}
			if (!starts && before != null)
			{
				s.segs.add(new Period(until, before.params));
				s.segs.sort((a, b) -> Integer.compare(a.from, b.from));
			}
		}
		for (Period p : s.segs)
		{
			if (p.from < until)
			{
				p.params.put(name, value);
			}
		}
		mergeSamePeriods(s, defaults);
	}

	/**
	 * Records that the game revealed some settings for a kill, for callers that already hold the boss under {@link #update}: the first
	 * kill each setting was read for is remembered, and a setting the boss infers (see {@code Boss.infer}) gives its first value to the
	 * earlier kills, unless the player set them.
	 *
	 * @param killNo the kill the game told about
	 * @param from   the first kill the value was given to (kills the plugin missed just before are assumed to be like this one)
	 */
	public static void noteReadLocked(BossState s, Boss boss, Map<String, Object> patch, int killNo, int from, Map<String, Object> defaults)
	{
		if (patch == null)
		{
			return;
		}
		for (Map.Entry<String, Object> e : patch.entrySet())
		{
			String k = e.getKey();
			if (!boss.reads.contains(k) || s.seenFrom(k) != null || e.getValue() == null)
			{
				continue;
			}
			if (s.seen == null)
			{
				s.seen = new LinkedHashMap<>();
			}
			s.seen.put(k, killNo);
			if (boss.infer.contains(k) && from > 1 && !setByPlayer(s, k, from))
			{
				setBeforeLocked(s, k, e.getValue(), from, defaults);
			}
		}
	}

	/**
	 * True when a period before the kill number says what the setting is (the player set it).
	 */
	static boolean setByPlayer(BossState s, String name, int until)
	{
		for (Period p : s.segs)
		{
			if (p.from < until && p.params.containsKey(name))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Merges all periods into one that has the latest settings.
	 */
	public void collapsePeriods(String id)
	{
		update(id, s ->
		{
			Period last = s.lastPeriod();
			s.segs = new ArrayList<>();
			s.segs.add(new Period(1, last.params));
			return null;
		});
	}

	/**
	 * Makes sure the settings that apply to the kill with this number include the given values (used when the game tells
	 * the plugin which mode the kill was in), for callers that already hold the boss under {@link #update}.  A new period
	 * starts at the kill when the settings differ.  Does nothing when a later period exists (the player has set things up
	 * by hand).
	 *
	 * @param defaults the boss's default settings, which apply where a period does not say (may be null)
	 */
	public static boolean applyParamsLocked(BossState s, int killNo, Map<String, Object> patch, Map<String, Object> defaults)
	{
		Period last = s.lastPeriod();
		if (last.from > killNo)
		{
			return false;
		}
		boolean differs = false;
		for (Map.Entry<String, Object> e : patch.entrySet())
		{
			Object cur = last.params.containsKey(e.getKey()) ? last.params.get(e.getKey()) : defaults == null ? null : defaults.get(e.getKey());
			if (!sameValue(cur, e.getValue()))
			{
				differs = true;
			}
		}
		if (!differs)
		{
			return false;
		}
		if (last.from == killNo)
		{
			last.params.putAll(patch);
			return true;
		}
		Period p = new Period(killNo, last.params);
		p.params.putAll(patch);
		s.segs.add(p);
		return true;
	}

	/**
	 * Gives one kill its own values for some settings, leaving the kills before and after it as they were (used for what the game
	 * reveals about a single kill, such as whether its loot went to you).  Periods are split around the kill and identical neighbours
	 * are merged again, so nothing piles up when the value matches.
	 *
	 * @return true when the ledger changed
	 */
	public static boolean setKillParamsLocked(BossState s, int killNo, Map<String, Object> patch, Map<String, Object> defaults)
	{
		if (killNo < 1 || patch == null || patch.isEmpty())
		{
			return false;
		}
		s.segs.sort((a, b) -> Integer.compare(a.from, b.from));
		int i = 0;
		for (int j = 0; j < s.segs.size(); j++)
		{
			if (s.segs.get(j).from <= killNo)
			{
				i = j;
			}
		}
		Period at = s.segs.get(i);
		boolean differs = false;
		for (Map.Entry<String, Object> e : patch.entrySet())
		{
			Object cur = at.params.containsKey(e.getKey()) ? at.params.get(e.getKey()) : defaults == null ? null : defaults.get(e.getKey());
			if (!sameValue(cur, e.getValue()))
			{
				differs = true;
			}
		}
		if (!differs)
		{
			return false;
		}
		Period next = i + 1 < s.segs.size() ? s.segs.get(i + 1) : null;
		if (next == null || next.from > killNo + 1)
		{
			// the kills after this one keep the values they had
			s.segs.add(i + 1, new Period(killNo + 1, at.params));
		}
		if (at.from == killNo)
		{
			at.params.putAll(patch);
		}
		else
		{
			Period mine = new Period(killNo, at.params);
			mine.params.putAll(patch);
			s.segs.add(i + 1, mine);
		}
		mergeSamePeriods(s, defaults);
		return true;
	}

	/**
	 * Merges neighbouring periods whose settings are the same.
	 */
	static void mergeSamePeriods(BossState s, Map<String, Object> defaults)
	{
		for (int j = s.segs.size() - 1; j > 0; j--)
		{
			if (sameParams(s.segs.get(j - 1).params, s.segs.get(j).params, defaults))
			{
				s.segs.remove(j);
			}
		}
	}

	private static boolean sameParams(Map<String, Object> a, Map<String, Object> b, Map<String, Object> defaults)
	{
		java.util.Set<String> keys = new java.util.HashSet<>(a.keySet());
		keys.addAll(b.keySet());
		for (String k : keys)
		{
			Object x = a.containsKey(k) ? a.get(k) : defaults == null ? null : defaults.get(k);
			Object y = b.containsKey(k) ? b.get(k) : defaults == null ? null : defaults.get(k);
			if (!sameValue(x, y))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Equality for setting values that may be a number, a boolean or a string; a missing value counts as "off" / 0.
	 */
	static boolean sameValue(Object a, Object b)
	{
		if (a == null)
		{
			a = 0;
		}
		if (b == null)
		{
			b = 0;
		}
		if (a instanceof String || b instanceof String)
		{
			return a.toString().equals(b.toString());
		}
		return num(a) == num(b);
	}

	private static double num(Object o)
	{
		if (o instanceof Boolean)
		{
			return (Boolean) o ? 1 : 0;
		}
		return ((Number) o).doubleValue();
	}

	public void setTracked(String id, String name, boolean on)
	{
		update(id, s ->
		{
			Boss boss = data.boss(id);
			boolean def = boss != null && Catalog.defaultTracked(boss).contains(name);
			if (on)
			{
				s.hide.remove(name);
				if (!def && !s.track.contains(name))
				{
					s.track.add(name);
				}
			}
			else
			{
				s.track.remove(name);
				if (def && !s.hide.contains(name))
				{
					s.hide.add(name);
				}
			}
			return null;
		});
	}

	/**
	 * Adds copies of an item the player owns without a known kill number (history).  Returns how many were added.
	 */
	public int addOwned(String id, String item, int n)
	{
		if (n <= 0)
		{
			return 0;
		}
		update(id, s ->
		{
			for (int i = 0; i < n; i++)
			{
				Drop d = new Drop();
				d.id = newId();
				d.item = item;
				d.qty = 1;
				d.t = now();
				d.src = Drop.SRC_IMPORT;
				s.drops.add(d);
			}
			s.hide.remove(item);
			Boss boss = data.boss(id);
			if (boss != null && !Catalog.defaultTracked(boss).contains(item) && !s.track.contains(item))
			{
				s.track.add(item);
			}
			return null;
		});
		return n;
	}

	/**
	 * Takes back copies that a history import added with no kill number (it counted every item of a stack as a drop, say).  Only
	 * those copies are touched, never one that was logged live or by hand.
	 *
	 * @return how many were removed
	 */
	public int removeOwned(String id, String item, int n)
	{
		if (n <= 0)
		{
			return 0;
		}
		final int[] removed = {0};
		update(id, s ->
		{
			for (java.util.Iterator<Drop> it = s.drops.iterator(); it.hasNext() && removed[0] < n; )
			{
				Drop d = it.next();
				if (d.item.equals(item) && d.kc == null && Drop.SRC_IMPORT.equals(d.src))
				{
					it.remove();
					removed[0]++;
				}
			}
			return null;
		});
		return removed[0];
	}

	/**
	 * Takes away copies of an item that the game's collection log shows fewer of than the ledger has.  The copies least likely to
	 * be right go first: those a history import added with no kill number, then those typed in by hand, then those seen in game.
	 * Within each, a copy that shares its kill with another copy of the item (counted twice) goes before the others, and then the
	 * one recorded last.
	 *
	 * @param importedOnly take only copies a history import added with no kill number
	 * @return how many were removed
	 */
	public int removeExtra(String id, String item, int n, boolean importedOnly)
	{
		if (n <= 0)
		{
			return 0;
		}
		return update(id, s ->
		{
			int removed = 0;
			while (removed < n)
			{
				Map<Integer, Integer> perKill = new HashMap<>();
				for (Drop d : s.drops)
				{
					if (d.item.equals(item) && d.kc != null)
					{
						perKill.merge(d.kc, 1, Integer::sum);
					}
				}
				Drop pick = null;
				for (Drop d : s.drops)
				{
					if (d.item.equals(item) && (!importedOnly || trust(d) == 0) && (pick == null || goesFirst(d, pick, perKill)))
					{
						pick = d;
					}
				}
				if (pick == null)
				{
					break;
				}
				s.drops.remove(pick);
				if (s.provDrops != null)
				{
					s.provDrops.remove(pick.id);
				}
				removed++;
			}
			return removed;
		});
	}

	/**
	 * 0 for a copy a history import added with no kill number, 1 for one typed in by hand, 2 for one seen in game.
	 */
	private static int trust(Drop d)
	{
		if (d.kc == null && Drop.SRC_IMPORT.equals(d.src))
		{
			return 0;
		}
		return Drop.SRC_MANUAL.equals(d.src) ? 1 : 2;
	}

	/**
	 * Of two copies of one item, whether a is taken away before b.
	 *
	 * @param perKill kill number to the copies of the item at it
	 */
	private static boolean goesFirst(Drop a, Drop b, Map<Integer, Integer> perKill)
	{
		if (trust(a) != trust(b))
		{
			return trust(a) < trust(b);
		}
		boolean twiceA = a.kc != null && perKill.get(a.kc) > 1;
		boolean twiceB = b.kc != null && perKill.get(b.kc) > 1;
		if (twiceA != twiceB)
		{
			return twiceA;
		}
		if (a.t != b.t)
		{
			return a.t > b.t;
		}
		return (a.kc == null ? 0 : a.kc) > (b.kc == null ? 0 : b.kc);
	}

	public void updatePrefs(java.util.function.Consumer<Prefs> fn)
	{
		synchronized (this)
		{
			fn.accept(prefs);
			prefs.window = Math.max(1, Math.min(5000, prefs.window));
			globalRev++;
			touch(null);
		}
		fire(null);
	}

	/**
	 * Whether the overview lists the boss (Settings, "Bosses to show").  Every boss is recorded either way.
	 */
	public synchronized boolean shown(String id)
	{
		return !prefs.hiddenBosses.contains(id);
	}

	/**
	 * Shows bosses on the overview or leaves them out.  No figure depends on it, so what was worked out stays valid.
	 */
	public void setShown(java.util.Collection<String> ids, boolean show)
	{
		synchronized (this)
		{
			boolean changed = false;
			for (String id : ids)
			{
				if (show)
				{
					changed |= prefs.hiddenBosses.remove(id);
				}
				else if (!prefs.hiddenBosses.contains(id))
				{
					prefs.hiddenBosses.add(id);
					changed = true;
				}
			}
			if (!changed)
			{
				return;
			}
			revision++;
		}
		fire(null);
	}

	/**
	 * Remembers what the side panel shows so that it comes back the same.  It is saved with the ledger; no figure depends on it.
	 */
	public void setView(String sel, String tab, String scope)
	{
		synchronized (this)
		{
			if (prefs.sel.equals(sel) && prefs.tab.equals(tab) && prefs.scope.equals(scope))
			{
				return;
			}
			prefs.sel = sel;
			prefs.tab = tab;
			prefs.scope = scope;
			revision++;
		}
		fire(null);
	}

	/**
	 * Adds what another ledger (a backup) has.  A boss this ledger knows nothing about comes over whole.  For the others the
	 * drops with ids not yet here are added, the kill count becomes the larger of the two, and the extra tracked items come over.
	 * Bosses the plugin leaves out come over only when this ledger does not have them yet.
	 *
	 * @return the number of drops added
	 */
	public int mergeFrom(Ledger other)
	{
		Map<String, BossState> theirs = new LinkedHashMap<>();
		Map<String, String> theirRetired;
		synchronized (other)
		{
			for (Map.Entry<String, BossState> e : other.bosses.entrySet())
			{
				theirs.put(e.getKey(), e.getValue().copy());
			}
			theirRetired = new LinkedHashMap<>(other.retired);
		}
		int added = 0;
		synchronized (this)
		{
			for (Map.Entry<String, String> e : theirRetired.entrySet())
			{
				retired.putIfAbsent(e.getKey(), e.getValue());
			}
			for (Map.Entry<String, BossState> e : theirs.entrySet())
			{
				String id = e.getKey();
				BossState inc = e.getValue();
				BossState cur = bosses.get(id);
				if (cur == null)
				{
					bosses.put(id, inc);
					added += inc.drops.size();
				}
				else
				{
					Set<String> have = new HashSet<>();
					for (Drop d : cur.drops)
					{
						have.add(d.id);
					}
					for (Drop d : inc.drops)
					{
						if (have.add(d.id))
						{
							cur.drops.add(d);
							added++;
						}
					}
					cur.kc = Math.max(cur.kc, Math.max(inc.kc, cur.maxDropKc()));
					for (String n : inc.track)
					{
						if (!cur.track.contains(n))
						{
							cur.track.add(n);
						}
					}
				}
				touch(id);
			}
		}
		fire(null);
		return added;
	}

	/**
	 * Replaces everything with the content of another ledger (restore).
	 */
	public void replaceWith(Ledger other)
	{
		synchronized (this)
		{
			Map<String, BossState> theirs;
			Map<String, String> theirRetired;
			Prefs p;
			synchronized (other)
			{
				theirs = new LinkedHashMap<>();
				for (Map.Entry<String, BossState> e : other.bosses.entrySet())
				{
					theirs.put(e.getKey(), e.getValue().copy());
				}
				theirRetired = new LinkedHashMap<>(other.retired);
				p = other.prefs.copy();
			}
			bosses.clear();
			bosses.putAll(theirs);
			retired.clear();
			retired.putAll(theirRetired);
			prefs = p;
			globalRev++;
			touch(null);
		}
		fire(null);
	}

	/**
	 * Adds the state of one boss from outside (restore of a single boss); replaces what is there.
	 */
	void putBoss(String id, BossState s)
	{
		synchronized (this)
		{
			bosses.put(id, s);
			revision++;
			revisions.merge(id, 1L, Long::sum);
		}
	}

	/**
	 * Keeps the JSON of a boss the plugin leaves out, to be written back on save.
	 */
	void putRetired(String id, String json)
	{
		synchronized (this)
		{
			retired.put(id, json);
		}
	}

	synchronized Map<String, String> rawRetired()
	{
		return retired;
	}

	void putPrefs(Prefs p)
	{
		synchronized (this)
		{
			this.prefs = p;
			globalRev++;
		}
	}

	synchronized Map<String, BossState> rawBosses()
	{
		return bosses;
	}

	synchronized Prefs rawPrefs()
	{
		return prefs;
	}

	public String newId()
	{
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < 8; i++)
		{
			b.append(Character.forDigit(rnd.nextInt(36), 36));
		}
		return b.toString();
	}
}
