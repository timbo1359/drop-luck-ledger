package com.dropluckledger.capture;

import com.dropluckledger.capture.BossIndex.KcLabel;
import com.dropluckledger.engine.Catalog;
import com.dropluckledger.engine.Data;
import com.dropluckledger.engine.Data.Boss;
import com.dropluckledger.engine.Data.Param;
import com.dropluckledger.engine.Engine;
import com.dropluckledger.ledger.Drop;
import com.dropluckledger.ledger.Ledger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns what the game reports (kill-count messages, loot, collection log and pet messages) into ledger entries.
 * <p>
 * The game tells the plugin the same kill in two ways, in either order: the chat line "Your X kill count is: N" and the
 * loot event.  The chat line is the better source for the kill number, so a loot event that arrives first is counted as
 * a provisional kill and corrected when the chat line follows.  A few bosses work differently, as their data says
 * ({@link Boss#lootmode}): rewards taken later that never count as kills (Tempoross), runs that only show as loot
 * (Doom of Mokhaiotl), and bosses whose loot goes to the top damage dealer only, where a kill without loot paid another player.
 * Everything here runs on the game thread; the ledger takes care of its own locking.
 */
public final class Capture
{
	private static final Logger log = LoggerFactory.getLogger(Capture.class);

	/**
	 * How long (in game ticks, 0.6 s each) a kill-count message waits for its loot.
	 */
	static final int SHORT_WINDOW = 100;
	/**
	 * Same for minigames and skilling bosses, where the reward is claimed some time after the kill count shows.
	 */
	static final int LONG_WINDOW = 3000;
	/**
	 * For a boss where a kill without loot means something (another player got it, the corpse was destroyed, the loot sacrificed):
	 * how long after the kill-count message the plugin waits for the loot before it decides there was none.  The loot appears a few
	 * ticks after the kill.
	 */
	static final int TOP_WINDOW = 50;
	/**
	 * How close (in ticks) a pet message must be to the kill it belongs to.
	 */
	static final int PET_WINDOW = 30;
	private static final int PROV_DROPS_KEPT = 20;
	/**
	 * A tick far enough in the past that nothing is near it (and subtracting it cannot overflow).
	 */
	private static final int NEVER = -1_000_000_000;

	/**
	 * Called for every drop that was written to the ledger.
	 */
	public interface Listener
	{
		void dropLogged(String bossId, String item, int killNo);
	}

	/**
	 * One item stack of a loot event, named the way the game names it.
	 */
	public static final class Stack
	{
		public final String name;
		public final int qty;

		public Stack(String name, int qty)
		{
			this.name = name;
			this.qty = qty;
		}
	}

	/**
	 * A loot event.
	 */
	public static final class Loot
	{
		public final String name;
		public final int amount;
		public final List<Stack> items;
		/**
		 * Boss settings the game revealed with the loot (delve level, skill levels, Colosseum waves), by setting name.
		 */
		public final Map<String, Object> hints;
		public final int tick;

		public Loot(String name, int amount, List<Stack> items, Map<String, Object> hints, int tick)
		{
			this.name = name;
			this.amount = amount;
			this.items = items;
			this.hints = hints == null ? Collections.emptyMap() : hints;
			this.tick = tick;
		}
	}

	private static final class Pending
	{
		final int killNo;
		final int tick;

		Pending(int killNo, int tick)
		{
			this.killNo = killNo;
			this.tick = tick;
		}
	}

	private static final class LastKill
	{
		final String bossId;
		final int killNo;
		final int tick;

		LastKill(String bossId, int killNo, int tick)
		{
			this.bossId = bossId;
			this.killNo = killNo;
			this.tick = tick;
		}
	}

	private final Ledger ledger;
	private final Data data;
	private final BossIndex index;
	private final Map<String, List<String>> logIndex;
	private final Map<String, Pending> pending = new HashMap<>();
	/**
	 * Kills of "top damage gets the loot" bosses marked as paying another player, by boss: kill number to the tick the mark was
	 * made, so loot that turns up late can still undo it.
	 */
	private final Map<String, Map<Integer, Integer>> markedNoLoot = new HashMap<>();
	private final Set<String> secondChance = new HashSet<>();
	private LastKill lastKill;
	private int petSeenTick = NEVER;
	private Listener listener;
	/**
	 * Whether RuneLite's Loot Tracker is on (as of the last tick).  Without it no loot is seen at all, so no kill is taken to have
	 * paid another player.
	 */
	private boolean lootTrackerOn;

	public Capture(Ledger ledger, BossIndex index)
	{
		this.ledger = ledger;
		this.data = ledger.data();
		this.index = index;
		this.logIndex = Catalog.logIndex(data);
	}

	public synchronized void setListener(Listener l)
	{
		this.listener = l;
	}

	/**
	 * Forget the kills waiting for their loot (login, world hop).
	 */
	public synchronized void reset()
	{
		pending.clear();
		markedNoLoot.clear();
		secondChance.clear();
		lastKill = null;
		petSeenTick = NEVER;
	}

	private static boolean longWindow(Boss b)
	{
		String c = b.cat == null ? "" : b.cat;
		return c.equals("Minigame") || c.equals("Minigame boss") || c.equals("Skilling boss");
	}

	private static int window(Boss b)
	{
		return longWindow(b) ? LONG_WINDOW : SHORT_WINDOW;
	}

	// ------------------------------------------------------------------ kill-count messages

	/**
	 * A "Your X kill count is: N" message.
	 *
	 * @param label the activity as the game wrote it
	 * @param n     the count it showed
	 */
	public synchronized void onKillCount(String label, int n, int tick)
	{
		KcLabel kl = index.kc(label);
		if (kl == null || n <= 0)
		{
			log.debug("Kill count for '{}' is not a boss in the ledger", label);
			return;
		}
		final String id = kl.bossId;
		final Boss boss = data.boss(id);
		if (boss.lootIsClaim())
		{
			return; // every claimed reward is one run; the game's own count measures something else
		}
		if (boss.decidesOnLoot())
		{
			Pending old = pending.get(id);
			if (old != null && lootTrackerOn)
			{
				decideNoLoot(id, old, tick); // the previous kill's loot never came
			}
		}
		final Map<String, Object> patch = new LinkedHashMap<>(kl.patch);
		final Boolean[] counted = {false};
		final int[] kill = {0};
		ledger.update(id, s ->
		{
			Map<String, Integer> labels = s.labels();
			Integer prev = labels.get(kl.label);
			int cur = s.kc;
			int prov = s.provisional();
			int newKc;
			if (prev == null)
			{
				// first count seen for this label: believe the game's number, but a message always means at least one new kill
				int target = s.labelSum(kl.label) + n;
				newKc = Math.max(prov > 0 ? cur : cur + 1, target);
			}
			else
			{
				if (n <= prev)
				{
					labels.put(kl.label, n); // the game's count went down or repeated: just follow it
					return null;
				}
				newKc = cur + (n - prev) - prov;
			}
			newKc = Math.max(newKc, s.maxDropKc());
			int shift = newKc - cur;
			if (prov > 0 && shift != 0 && s.provDrops != null)
			{
				for (Drop d : s.drops)
				{
					if (s.provDrops.contains(d.id) && d.kc != null)
					{
						d.kc = Math.max(1, d.kc + shift);
					}
				}
			}
			if (prov > 0 && shift != 0)
			{
				// what the game told about the kills counted from their loot moves with them
				int firstProv = cur - prov + 1;
				if (s.seen != null)
				{
					for (Map.Entry<String, Integer> e : s.seen.entrySet())
					{
						if (e.getValue() >= firstProv)
						{
							e.setValue(Math.max(1, e.getValue() + shift));
						}
					}
				}
				if (s.liveFrom != null && s.liveFrom >= firstProv)
				{
					s.liveFrom = Math.max(1, s.liveFrom + shift);
				}
			}
			labels.put(kl.label, n);
			int gap = newKc - cur;
			s.kc = newKc;
			s.prov = null;
			s.provDrops = null;
			if (s.liveFrom == null)
			{
				s.liveFrom = newKc; // the first kill the plugin saw itself
			}
			// kills the plugin did not see (first count, missed kills) are assumed to be in the same mode as this one
			int from = prov == 0 && gap > 1 ? cur + 1 : newKc;
			Ledger.applyParamsLocked(s, from, patch, Engine.defaultParams(boss));
			Ledger.noteReadLocked(s, boss, patch, newKc, from, Engine.defaultParams(boss));
			kill[0] = newKc;
			counted[0] = true;
			if (prov == 0)
			{
				pending.put(id, new Pending(newKc, tick));
			}
			else if (boss.decidesOnLoot() && !boss.onLoot.isEmpty())
			{
				// the loot came first: this kill's loot is already known
				Ledger.setKillParamsLocked(s, newKc, Boss.settings(boss.onLoot), Engine.defaultParams(boss));
				Ledger.noteReadLocked(s, boss, Boss.settings(boss.onLoot), newKc, newKc, Engine.defaultParams(boss));
			}
			return null;
		});
		if (counted[0])
		{
			killSeen(id, kill[0], tick);
		}
	}

	/**
	 * Called every game tick: kills of bosses where a kill without loot means something (see {@link Boss#noLoot}) whose loot has not
	 * come within the wait are marked so.  Nothing is decided while the Loot Tracker is off, because then no loot is seen at all.
	 */
	public synchronized void onTick(int tick, boolean lootTrackerOn)
	{
		this.lootTrackerOn = lootTrackerOn;
		if (pending.isEmpty() && markedNoLoot.isEmpty())
		{
			return;
		}
		Iterator<Map.Entry<String, Pending>> it = pending.entrySet().iterator();
		List<Object[]> decide = new ArrayList<>();
		while (it.hasNext())
		{
			Map.Entry<String, Pending> e = it.next();
			Boss b = data.boss(e.getKey());
			Pending p = e.getValue();
			if (b == null)
			{
				it.remove();
				continue;
			}
			if (b.decidesOnLoot() && tick - p.tick > TOP_WINDOW)
			{
				it.remove();
				if (lootTrackerOn)
				{
					decide.add(new Object[] {e.getKey(), p});
				}
			}
			else if (tick - p.tick > window(b))
			{
				it.remove();
			}
		}
		for (Object[] d : decide)
		{
			decideNoLoot((String) d[0], (Pending) d[1], tick);
		}
		for (Iterator<Map.Entry<String, Map<Integer, Integer>>> bi = markedNoLoot.entrySet().iterator(); bi.hasNext(); )
		{
			Map<Integer, Integer> m = bi.next().getValue();
			m.values().removeIf(t -> tick - t > SHORT_WINDOW);
			if (m.isEmpty())
			{
				bi.remove();
			}
		}
	}

	private void decideNoLoot(String bossId, Pending p, int tick)
	{
		pending.remove(bossId);
		Boss boss = data.boss(bossId);
		if (boss == null || !boss.decidesOnLoot())
		{
			return;
		}
		ledger.update(bossId, s ->
		{
			Ledger.setKillParamsLocked(s, p.killNo, Boss.settings(boss.noLoot), Engine.defaultParams(boss));
			Ledger.noteReadLocked(s, boss, Boss.settings(boss.noLoot), p.killNo, p.killNo, Engine.defaultParams(boss));
			return null;
		});
		markedNoLoot.computeIfAbsent(bossId, k -> new HashMap<>()).put(p.killNo, tick);
		log.debug("{} kill {}: no loot came ({})", bossId, p.killNo, boss.noLoot);
	}

	// ------------------------------------------------------------------ loot

	/**
	 * Loot from a boss.  Items the player tracks are written to the ledger with the kill number.
	 */
	public void onLoot(Loot e)
	{
		final String id = index.loot(e.name);
		if (id == null)
		{
			return;
		}
		final Boss boss = data.boss(id);
		final boolean extra = boss.lootIsExtra() || index.isExtraLoot(id, e.name);
		final int kills = Math.max(1, e.amount);
		final int[] kill = {0};
		List<String> logged;
		synchronized (this)
		{
			logged = ledger.update(id, s ->
			{
				int killNo;
				boolean provisional = false;
				Map<String, Object> patch = hintPatch(boss, e.hints);
				if (extra)
				{
					// rewards of kills already counted (a reward cart, the bodyguards): the latest kill gets them
					killNo = Math.max(1, s.kc);
				}
				else if (boss.lootIsClaim())
				{
					s.kc += kills;
					killNo = s.kc;
				}
				else
				{
					// for a boss where a kill without loot means something, a clue or pet alone is not loot (a sacrifice can still give the pet)
					final boolean regular = !boss.decidesOnLoot() || hasRegularLoot(boss, id, e.items);
					Pending p = pending.remove(id);
					Map<Integer, Integer> marked = markedNoLoot.get(id);
					Integer late = null;
					if (p == null && marked != null && !marked.isEmpty())
					{
						// loot after the plugin had decided there was none: it belongs to that kill after all
						int newest = Collections.max(marked.keySet());
						if (e.tick - marked.get(newest) <= SHORT_WINDOW)
						{
							late = newest;
							if (regular)
							{
								marked.remove(newest);
							}
						}
					}
					if (p != null && e.tick >= p.tick && e.tick - p.tick <= window(boss))
					{
						s.kc += kills - 1;
						killNo = s.kc;
					}
					else if (late != null)
					{
						killNo = late;
					}
					else
					{
						s.kc += kills;
						s.prov = s.provisional() + kills;
						killNo = s.kc;
						provisional = true;
					}
					if (boss.decidesOnLoot() && !provisional)
					{
						Map<String, String> says = regular ? boss.onLoot : boss.noLoot;
						if (!says.isEmpty())
						{
							Ledger.setKillParamsLocked(s, killNo, Boss.settings(says), Engine.defaultParams(boss));
							Ledger.noteReadLocked(s, boss, Boss.settings(says), killNo, killNo, Engine.defaultParams(boss));
						}
					}
				}
				if (!extra && s.liveFrom == null)
				{
					s.liveFrom = killNo;
				}
				Ledger.applyParamsLocked(s, killNo, patch, Engine.defaultParams(boss));
				Ledger.noteReadLocked(s, boss, patch, killNo, killNo, Engine.defaultParams(boss));
				Set<String> tracked = new HashSet<>(ledger.trackedNames(id));
				List<String> out = new ArrayList<>();
				for (Stack stack : e.items)
				{
					String item = index.item(id, stack.name);
					if (item == null || !tracked.contains(item))
					{
						continue;
					}
					if (secondChance.remove(id + '|' + item + '|' + killNo))
					{
						continue; // the collection log message already put this copy in
					}
					Drop d = ledger.addDropLocked(id, s, item, killNo, stack.qty, snapFor(id, item), Drop.SRC_LIVE);
					if (provisional)
					{
						if (s.provDrops == null)
						{
							s.provDrops = new ArrayList<>();
						}
						s.provDrops.add(d.id);
						while (s.provDrops.size() > PROV_DROPS_KEPT)
						{
							s.provDrops.remove(0);
						}
					}
					out.add(item);
				}
				kill[0] = killNo;
				return out;
			});
			for (String item : logged)
			{
				notifyLogged(id, item, kill[0]);
			}
			if (!extra)
			{
				killSeen(id, kill[0], e.tick);
			}
		}
	}

	/**
	 * True when the loot has anything besides what only the tertiary and pet rolls give.
	 */
	private boolean hasRegularLoot(Boss boss, String bossId, List<Stack> items)
	{
		for (Stack st : items)
		{
			String item = index.item(bossId, st.name);
			if (item == null || !boss.tertiaryOnly().contains(item))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The settings the game revealed with a loot event, restricted to the ones this boss has.
	 */
	static Map<String, Object> hintPatch(Boss boss, Map<String, Object> hints)
	{
		Map<String, Object> out = new LinkedHashMap<>();
		if (hints == null || hints.isEmpty())
		{
			return out;
		}
		for (Param p : boss.params)
		{
			Object v = hints.get(p.name);
			if (v == null)
			{
				continue;
			}
			if ("bool".equals(p.type))
			{
				out.put(p.name, truthy(v) ? 1 : 0);
			}
			else if ("int".equals(p.type) && v instanceof Number)
			{
				long r = Math.round(((Number) v).doubleValue());
				if (p.min != null)
				{
					r = Math.max(r, Math.round(p.min));
				}
				if (p.max != null)
				{
					r = Math.min(r, Math.round(p.max));
				}
				out.put(p.name, (int) r);
			}
			else if ("select".equals(p.type) && v instanceof String && p.options != null && p.options.contains(v))
			{
				out.put(p.name, v);
			}
		}
		return out;
	}

	private static boolean truthy(Object v)
	{
		if (v instanceof Boolean)
		{
			return (Boolean) v;
		}
		if (v instanceof Number)
		{
			return ((Number) v).doubleValue() != 0;
		}
		return v != null;
	}

	/**
	 * For an item that several bosses drop: the other bosses' kill counts right now.
	 */
	private Map<String, Integer> snapFor(String bossId, String item)
	{
		List<String> ids = logIndex.get(item);
		if (ids == null || ids.size() < 2)
		{
			return null;
		}
		Map<String, Integer> snap = new LinkedHashMap<>();
		for (String other : ids)
		{
			if (!other.equals(bossId))
			{
				int k = ledger.kc(other);
				if (k > 0)
				{
					snap.put(other, k);
				}
			}
		}
		return snap.isEmpty() ? null : snap;
	}

	private void notifyLogged(String bossId, String item, int killNo)
	{
		Listener l = listener;
		if (l != null)
		{
			try
			{
				l.dropLogged(bossId, item, killNo);
			}
			catch (RuntimeException ex)
			{
				log.debug("Drop listener failed", ex);
			}
		}
	}

	// ------------------------------------------------------------------ pets and collection log

	/**
	 * A "New item added to your collection log: X" message.  Used for pets, which do not appear in loot events, and as a
	 * second chance for tracked items of the boss that was just killed.
	 */
	public synchronized void onCollectionLog(String itemName, int tick)
	{
		if (itemName == null || lastKill == null || tick - lastKill.tick > PET_WINDOW)
		{
			// the message may come first; remember it only for pets
			return;
		}
		recordIfTracked(lastKill.bossId, lastKill.killNo, itemName);
	}

	/**
	 * A "funny feeling" pet message (it does not say which pet).  Counts when the boss just killed has exactly one pet.
	 */
	public synchronized void onPetMessage(int tick)
	{
		if (lastKill != null && tick - lastKill.tick <= PET_WINDOW)
		{
			recordIfTracked(lastKill.bossId, lastKill.killNo, null);
		}
		else
		{
			petSeenTick = tick;
		}
	}

	private void killSeen(String bossId, int killNo, int tick)
	{
		lastKill = new LastKill(bossId, killNo, tick);
		if (tick - petSeenTick <= PET_WINDOW && tick >= petSeenTick)
		{
			petSeenTick = NEVER;
			recordIfTracked(bossId, killNo, null);
		}
	}

	/**
	 * Logs the item (or the boss's only pet when itemName is null) at the kill, unless that copy is already there.
	 */
	private void recordIfTracked(String bossId, int killNo, String itemName)
	{
		Boss boss = data.boss(bossId);
		if (boss == null)
		{
			return;
		}
		List<String> logged = ledger.update(bossId, s ->
		{
			List<String> tracked = ledger.trackedNames(bossId);
			String item = null;
			if (itemName != null)
			{
				item = index.item(bossId, itemName);
				if (item == null || !tracked.contains(item))
				{
					return Collections.<String>emptyList();
				}
			}
			else
			{
				for (String n : tracked)
				{
					if (isPet(boss, n))
					{
						if (item != null)
						{
							return Collections.<String>emptyList(); // more than one pet: cannot tell which
						}
						item = n;
					}
				}
				if (item == null)
				{
					return Collections.<String>emptyList();
				}
			}
			for (Drop d : s.drops)
			{
				if (d.item.equals(item) && d.kc != null && d.kc == killNo)
				{
					return Collections.<String>emptyList();
				}
			}
			ledger.addDropLocked(bossId, s, item, killNo, 1, snapFor(bossId, item), Drop.SRC_LIVE);
			if (secondChance.size() > 200)
			{
				secondChance.clear();
			}
			secondChance.add(bossId + '|' + item + '|' + killNo);
			return Collections.singletonList(item);
		});
		for (String item : logged)
		{
			notifyLogged(bossId, item, killNo);
		}
	}

	private static boolean isPet(Boss b, String item)
	{
		Engine.ItemMeta m = b.meta().get(item);
		return m != null && m.isPet();
	}
}
