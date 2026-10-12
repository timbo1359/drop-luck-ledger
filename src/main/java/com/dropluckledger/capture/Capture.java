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
 * a provisional kill and corrected when the chat line follows.  Everything here runs on the game thread; the ledger takes
 * care of its own locking.
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
		 * Boss settings the game revealed with the loot (raid level, delve level, skill levels), by setting name.
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
	private final Set<String> secondChance = new HashSet<>();
	private LastKill lastKill;
	private int petSeenTick = NEVER;
	private Listener listener;

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
		secondChance.clear();
		lastKill = null;
		petSeenTick = NEVER;
	}

	private static boolean longWindow(Boss b)
	{
		String c = b.cat == null ? "" : b.cat;
		return c.equals("Raid") || c.equals("Minigame") || c.equals("Minigame boss") || c.equals("Skilling boss");
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
			labels.put(kl.label, n);
			int gap = newKc - cur;
			s.kc = newKc;
			s.prov = null;
			s.provDrops = null;
			// kills the plugin did not see (first count, missed kills) are assumed to be in the same mode as this one
			int from = prov == 0 && gap > 1 ? cur + 1 : newKc;
			Ledger.applyParamsLocked(s, from, kl.patch, Engine.defaultParams(data.boss(id)));
			kill[0] = newKc;
			counted[0] = true;
			if (prov == 0)
			{
				pending.put(id, new Pending(newKc, tick));
			}
			return null;
		});
		if (counted[0])
		{
			killSeen(id, kill[0], tick);
		}
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
		final int kills = Math.max(1, e.amount);
		final int[] kill = {0};
		List<String> logged;
		synchronized (this)
		{
			logged = ledger.update(id, s ->
			{
				Pending p = pending.remove(id);
				int killNo;
				boolean provisional;
				if (p != null && e.tick >= p.tick && e.tick - p.tick <= (longWindow(boss) ? LONG_WINDOW : SHORT_WINDOW))
				{
					s.kc += kills - 1;
					killNo = s.kc;
					provisional = false;
				}
				else
				{
					s.kc += kills;
					s.prov = s.provisional() + kills;
					killNo = s.kc;
					provisional = true;
				}
				Map<String, Object> patch = hintPatch(boss, e.hints);
				Ledger.applyParamsLocked(s, killNo, patch, Engine.defaultParams(boss));
				Set<String> tracked = new HashSet<>(ledger.trackedNames(id));
				List<String> out = new ArrayList<>();
				for (Stack st : e.items)
				{
					String item = index.item(id, st.name);
					if (item == null || !tracked.contains(item))
					{
						continue;
					}
					if (secondChance.remove(id + '|' + item + '|' + killNo))
					{
						continue; // the collection log message already put this copy in
					}
					Drop d = ledger.addDropLocked(id, s, item, killNo, st.qty, snapFor(id, item), Drop.SRC_LIVE);
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
			killSeen(id, kill[0], e.tick);
		}
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
