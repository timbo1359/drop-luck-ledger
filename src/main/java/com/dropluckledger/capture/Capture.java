package com.dropluckledger.capture;

import com.dropluckledger.capture.BossIndex.KcLabel;
import com.dropluckledger.engine.Catalog;
import com.dropluckledger.engine.Data;
import com.dropluckledger.engine.Data.Boss;
import com.dropluckledger.engine.Data.Param;
import com.dropluckledger.engine.Engine;
import com.dropluckledger.ledger.BossState;
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
 * Everything here runs on the game thread, except adding the counts the player agreed to from a screen ({@link #onShownCount}); the
 * methods are synchronized, and the ledger takes care of its own locking.
 */
public final class Capture
{
	private static final Logger log = LoggerFactory.getLogger(Capture.class);

	/**
	 * How long (in game ticks, 0.6 s each) a kill-count message waits for its loot.
	 */
	static final int SHORT_WINDOW = 100;
	/**
	 * Same for raids, minigames and skilling bosses, where the reward is claimed some time after the kill count shows.
	 */
	static final int LONG_WINDOW = 3000;
	/**
	 * After a raid's kill-count message, how long a collection log or pet message still belongs to the raid: the chest is opened a
	 * minute or two after the last boss dies (the loot itself, when the Loot Tracker reports it, counts from its own tick).
	 */
	static final int RAID_REWARD_WINDOW = 1000;
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
	 * Told about every loot of a boss, with all its items (tracked or not), once its kill number is settled: the drop log.  Pets,
	 * which do not show in the loot, come as a loot of their own.
	 */
	public interface LootLog
	{
		/**
		 * @param t     epoch milliseconds when the loot came
		 * @param items every item, named as the game names it (a pet with the boss data's name)
		 * @param snap  for items several bosses drop, the other bosses' kill counts; null when none of the items is shared
		 */
		void loot(long t, String bossId, int killNo, List<Stack> items, Map<String, Integer> snap);
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

	/**
	 * A loot counted as a kill of its own before its kill-count message came: logged once the message settles the kill number.
	 */
	private static final class Held
	{
		final long t;
		final int killNo;
		final List<Stack> items;
		final Map<String, Integer> snap;
		final int tick;

		Held(long t, int killNo, List<Stack> items, Map<String, Integer> snap, int tick)
		{
			this.t = t;
			this.killNo = killNo;
			this.items = items;
			this.snap = snap;
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
	private LootLog lootLog;
	private final Map<String, List<Held>> held = new HashMap<>();
	/**
	 * Pets already logged (boss, pet and kill), so that the pet message and the collection log message log it once.
	 */
	private final Set<String> petsLogged = new HashSet<>();
	/**
	 * For a boss that counts the loots of its other monsters (EXTRACOUNT: the God Wars bodyguards), by boss: the kill the loots go to
	 * (the latest one seen since login, as their drops do) and how many have come.  The count is that kill's own setting.
	 */
	private final Map<String, int[]> extraCounted = new HashMap<>();
	/**
	 * Those loots that came before the first kill seen since login: they are counted for that kill.
	 */
	private final Map<String, Integer> extraEarly = new HashMap<>();
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

	public synchronized void setLootLog(LootLog l)
	{
		this.lootLog = l;
	}

	/**
	 * Forget the kills waiting for their loot (login, world hop).
	 */
	public synchronized void reset()
	{
		for (String id : new ArrayList<>(held.keySet()))
		{
			release(id, 0);
		}
		pending.clear();
		markedNoLoot.clear();
		secondChance.clear();
		extraCounted.clear();
		extraEarly.clear();
		lastKill = null;
		petSeenTick = NEVER;
	}

	/**
	 * The most the boss's EXTRACOUNT setting can be (3 bodyguards).
	 */
	private static int extraMax(Boss boss)
	{
		for (Param p : boss.params)
		{
			if (p.name.equals(boss.extraCount))
			{
				return p.max == null ? Integer.MAX_VALUE : (int) Math.round(p.max);
			}
		}
		return Integer.MAX_VALUE;
	}

	/**
	 * A kill of a boss with an EXTRACOUNT setting, seen as it happened: it starts with the loots that came before it since login (none
	 * most of the time), and the ones that come next are added to it.  For callers that hold the boss under {@link Ledger#update}.
	 */
	private void extraKillLocked(BossState s, Boss boss, String bossId, int killNo)
	{
		if (boss.extraCount == null || boss.extraCount.isEmpty())
		{
			return;
		}
		Integer early = extraEarly.remove(bossId);
		int n = Math.min(extraMax(boss), early == null ? 0 : early);
		extraCounted.put(bossId, new int[] {killNo, n});
		Map<String, Object> patch = Collections.singletonMap(boss.extraCount, (Object) n);
		Map<String, Object> defaults = Engine.defaultParams(boss);
		Ledger.setKillParamsLocked(s, killNo, patch, defaults);
		Ledger.noteReadLocked(s, boss, patch, killNo, killNo, defaults);
	}

	/**
	 * A loot of one of the boss's other monsters (a bodyguard): one more for the kill its drops go to, when that kill was seen since
	 * login, or for the next kill.  For callers that hold the boss under {@link Ledger#update}.
	 */
	private void extraLootLocked(BossState s, Boss boss, String bossId, int count)
	{
		int max = extraMax(boss);
		int[] at = extraCounted.get(bossId);
		if (at != null && at[0] == s.kc)
		{
			at[1] = Math.min(max, at[1] + count);
			Ledger.setKillParamsLocked(s, at[0], Collections.singletonMap(boss.extraCount, (Object) at[1]), Engine.defaultParams(boss));
		}
		else
		{
			extraEarly.merge(bossId, count, (a, b) -> Math.min(max, a + b));
		}
	}

	private static boolean longWindow(Boss b)
	{
		String c = b.cat == null ? "" : b.cat;
		return isRaid(b) || c.equals("Minigame") || c.equals("Minigame boss") || c.equals("Skilling boss");
	}

	private static boolean isRaid(Boss b)
	{
		return "Raid".equals(b.cat);
	}

	/**
	 * How long after the last kill a collection log or pet message still belongs to it.
	 */
	private int rewardWindow(LastKill k)
	{
		Boss b = data.boss(k.bossId);
		return b != null && isRaid(b) ? RAID_REWARD_WINDOW : PET_WINDOW;
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
		final int[] moved = {0};
		final boolean[] lootFirst = {false};
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
			lootFirst[0] = prov > 0;
			moved[0] = shift;
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
			extraKillLocked(s, boss, id, newKc);
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
			if (lootFirst[0])
			{
				release(id, moved[0]);
			}
			killSeen(id, kill[0], tick);
		}
	}

	/**
	 * True while a kill of the boss waits for its loot (a raid's until the chest is opened): its loot will take the kill count as it
	 * then is, so the count is not changed from a screen meanwhile.
	 */
	public synchronized boolean awaitingLoot(String bossId)
	{
		return pending.containsKey(bossId);
	}

	/**
	 * What a count the game showed on screen did to the ledger.
	 */
	public static final class Raised
	{
		public final String bossId;
		/**
		 * The ledger's kill count before the count was read.
		 */
		public final int from;
		/**
		 * The ledger's kill count after.
		 */
		public final int to;

		public Raised(String bossId, int from, int to)
		{
			this.bossId = bossId;
			this.from = from;
			this.to = to;
		}
	}

	/**
	 * A kill count the game shows on a screen (the Boss Kill Log, a collection log page), which the player agreed to add.  It is not a
	 * new kill, but a count higher than the last one the ledger saw for that label means kills the plugin missed (made on another
	 * computer, or with the plugin off).  They are added at once, with no drops on them, as the next kill-count message would add them.
	 * A count the ledger already has, or a lower one, changes nothing.  May be called off the client thread.
	 *
	 * @param shown the activity as the screen wrote it ("Zulrah", "Chambers of Xeric (CM) completions")
	 * @return what changed, or null when the kill count did not
	 */
	public synchronized Raised onShownCount(String shown, int n)
	{
		KcLabel kl = index.kcShown(shown);
		if (kl == null || n <= 0)
		{
			return null;
		}
		final String id = kl.bossId;
		final Boss boss = data.boss(id);
		if (boss == null || boss.lootIsClaim())
		{
			return null; // every claimed reward is one run; the game's own count measures something else
		}
		if (pending.containsKey(id))
		{
			return null; // a kill whose loot is still to come: the next look at the screen catches up instead
		}
		BossState now = ledger.copyOf(id);
		Integer last = now.labelKc == null ? null : now.labelKc.get(kl.label);
		if (last != null && n <= last)
		{
			return null;
		}
		final Map<String, Object> defaults = Engine.defaultParams(boss);
		int[] change = ledger.update(id, s ->
		{
			int[] c = raiseLocked(s, kl.label, n);
			if (c != null)
			{
				// the kills missed are in the mode the label says (normal or awakened, challenge mode ...)
				Ledger.applyParamsLocked(s, c[0] + 1, kl.patch, defaults);
			}
			return c;
		});
		release(id, 0); // the loot counted as kills of their own keeps its kill numbers
		if (change == null)
		{
			return null;
		}
		log.debug("{}: {} kills the ledger missed, from the count on screen", id, change[1] - change[0]);
		return new Raised(id, change[0], change[1]);
	}

	/**
	 * What a count the game shows for one kill-count label makes of a boss's kill count, the same steps whether the ledger is being
	 * changed or a copy of it is being looked at: the kills since the count last seen for the label are added, or, for a label never
	 * seen, the game's count with the other labels' counts.  The labels and the kill count of the state given change.
	 *
	 * @return {kill count before, kill count after}, or null when the count adds no kill
	 */
	public static int[] raiseLocked(BossState s, String label, int n)
	{
		Map<String, Integer> labels = s.labels();
		Integer prev = labels.get(label);
		if (prev != null && n <= prev)
		{
			return null;
		}
		int cur = s.kc;
		// kills counted from their loot alone are in the game's count too
		int target = prev == null ? s.labelSum(label) + n : cur - s.provisional() + (n - prev);
		labels.put(label, n);
		s.prov = null;
		s.provDrops = null;
		if (target <= cur)
		{
			return null;
		}
		s.kc = target;
		return new int[]{cur, target};
	}

	/**
	 * Called every game tick: kills of bosses where a kill without loot means something (see {@link Boss#noLoot}) whose loot has not
	 * come within the wait are marked so.  Nothing is decided while the Loot Tracker is off, because then no loot is seen at all.
	 */
	public synchronized void onTick(int tick, boolean lootTrackerOn)
	{
		this.lootTrackerOn = lootTrackerOn;
		for (String id : new ArrayList<>(held.keySet()))
		{
			Boss b = data.boss(id);
			List<Held> hs = held.get(id);
			if (b == null || !hs.isEmpty() && tick - hs.get(hs.size() - 1).tick > window(b))
			{
				release(id, 0); // the kill-count message never came: the loot keeps the kill number it was counted at
			}
		}
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
		final boolean[] counted = {false};
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
					if (boss.extraCount != null && !boss.extraCount.isEmpty() && index.isExtraLoot(id, e.name))
					{
						extraLootLocked(s, boss, id, kills);
					}
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
				counted[0] = provisional;
				return out;
			});
			logLoot(id, kill[0], e, counted[0]);
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

	// ------------------------------------------------------------------ the drop log

	/**
	 * Hands the whole loot to the drop log, or holds it while its kill number may still move (the loot came before its kill-count
	 * message).
	 */
	private void logLoot(String bossId, int killNo, Loot e, boolean provisional)
	{
		if (lootLog == null || e.items.isEmpty())
		{
			return;
		}
		List<Stack> items = new ArrayList<>(e.items);
		Held h = new Held(ledger.now(), killNo, items, lootSnap(bossId, items), e.tick);
		if (provisional)
		{
			held.computeIfAbsent(bossId, k -> new ArrayList<>()).add(h);
		}
		else
		{
			emit(bossId, h, 0);
		}
	}

	/**
	 * Logs the loot held for a boss, its kill numbers moved as the kill-count message moved the kills counted from their loot.
	 */
	private void release(String bossId, int shift)
	{
		List<Held> hs = held.remove(bossId);
		if (hs == null)
		{
			return;
		}
		for (Held h : hs)
		{
			emit(bossId, h, shift);
		}
	}

	private void emit(String bossId, Held h, int shift)
	{
		LootLog l = lootLog;
		if (l == null)
		{
			return;
		}
		try
		{
			l.loot(h.t, bossId, Math.max(1, h.killNo + shift), h.items, h.snap);
		}
		catch (RuntimeException ex)
		{
			log.debug("Drop log failed", ex);
		}
	}

	/**
	 * The other bosses' kill counts, for the items of the loot that several bosses drop.
	 */
	private Map<String, Integer> lootSnap(String bossId, List<Stack> items)
	{
		Map<String, Integer> snap = null;
		for (Stack st : items)
		{
			String item = index.item(bossId, st.name);
			Map<String, Integer> s = item == null ? null : snapFor(bossId, item);
			if (s != null)
			{
				if (snap == null)
				{
					snap = new LinkedHashMap<>();
				}
				snap.putAll(s);
			}
		}
		return snap;
	}

	/**
	 * Logs a pet the player got, which never shows in the loot: the one the message names, or the boss's only pet.
	 */
	private void logPet(Boss boss, String bossId, int killNo, String itemName)
	{
		if (lootLog == null)
		{
			return;
		}
		String pet = null;
		if (itemName != null)
		{
			String item = index.item(bossId, itemName);
			if (item == null || !isPet(boss, item))
			{
				return; // other items come with the loot
			}
			pet = item;
		}
		else
		{
			for (Map.Entry<String, Engine.ItemMeta> m : boss.meta().entrySet())
			{
				if (m.getValue().isPet() && !m.getValue().hidden)
				{
					if (pet != null)
					{
						return; // more than one pet: cannot tell which
					}
					pet = m.getKey();
				}
			}
		}
		if (pet == null)
		{
			return;
		}
		if (petsLogged.size() > 200)
		{
			petsLogged.clear();
		}
		if (petsLogged.add(bossId + '|' + pet + '|' + killNo))
		{
			emit(bossId, new Held(ledger.now(), killNo, Collections.singletonList(new Stack(pet, 1)), snapFor(bossId, pet), 0), 0);
		}
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
		if (itemName == null || lastKill == null || tick - lastKill.tick > rewardWindow(lastKill))
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
		if (lastKill != null && tick - lastKill.tick <= rewardWindow(lastKill))
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
		logPet(boss, bossId, killNo, itemName);
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
