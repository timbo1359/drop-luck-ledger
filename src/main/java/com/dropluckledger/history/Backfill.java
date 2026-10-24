package com.dropluckledger.history;

import com.dropluckledger.capture.BossIndex;
import com.dropluckledger.engine.Catalog;
import com.dropluckledger.ledger.Drop;
import com.dropluckledger.ledger.Ledger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Brings in, from the drop log, the copies of an item the player has just started to track.  The plugin logs every loot of its
 * bosses, tracked items or not, so the copies received before are there with the kill they came on:
 * <ul>
 * <li>a copy the ledger already has at that kill is left as it is;</li>
 * <li>otherwise a copy the ledger has with no kill number, brought in from game history after the loot came (so it counted that loot),
 * takes that kill number;</li>
 * <li>otherwise the copy is added, as seen in game.</li>
 * </ul>
 * Loot logged at a kill past the ledger's kill count is left out: it belongs to a ledger that has been replaced since.
 */
public final class Backfill
{
	private Backfill()
	{
	}

	/**
	 * What the drop log added.
	 */
	public static final class Result
	{
		/**
		 * Copies added.
		 */
		public int added;
		/**
		 * Copies with no kill number that got one.
		 */
		public int numbered;

		public boolean isEmpty()
		{
			return added == 0 && numbered == 0;
		}
	}

	/**
	 * @param log the logged loot (of any boss; only this boss's is used)
	 */
	public static Result apply(Ledger ledger, BossIndex index, String bossId, String item, List<LoggedLoot> log)
	{
		// kill number to the stacks of the item that came on it (a stack is one drop, as when it is recorded live)
		TreeMap<Integer, List<LoggedLoot.Item>> stacks = new TreeMap<>();
		Map<Integer, Map<String, Integer>> snaps = new LinkedHashMap<>();
		Map<Integer, Long> times = new LinkedHashMap<>();
		for (LoggedLoot l : log)
		{
			if (!bossId.equals(l.bossId) || l.kill < 1)
			{
				continue;
			}
			for (LoggedLoot.Item it : l.items)
			{
				if (it.qty > 0 && item.equals(index.item(bossId, it.name)))
				{
					stacks.computeIfAbsent(l.kill, k -> new ArrayList<>()).add(it);
					times.merge(l.kill, l.t, Math::min);
					if (l.snap != null)
					{
						snaps.put(l.kill, l.snap);
					}
				}
			}
		}
		Result r = new Result();
		if (stacks.isEmpty())
		{
			return r;
		}
		List<String> sharedWith = Catalog.logIndex(ledger.data()).get(item);
		ledger.update(bossId, s ->
		{
			for (Map.Entry<Integer, List<LoggedLoot.Item>> e : stacks.entrySet())
			{
				int kill = e.getKey();
				if (kill > s.kc)
				{
					continue;
				}
				int have = 0;
				for (Drop d : s.drops)
				{
					if (d.item.equals(item) && d.kc != null && d.kc == kill)
					{
						have++;
					}
				}
				List<LoggedLoot.Item> want = e.getValue();
				long when = times.get(kill);
				for (int i = have; i < want.size(); i++)
				{
					// an imported copy stands for this one only if the import came after it
					Drop unknown = null;
					for (Drop d : s.drops)
					{
						if (d.item.equals(item) && d.kc == null && Drop.SRC_IMPORT.equals(d.src) && d.t >= when)
						{
							unknown = d;
							break;
						}
					}
					if (unknown != null)
					{
						unknown.kc = kill;
						unknown.qty = Math.max(1, want.get(i).qty);
						unknown.src = Drop.SRC_LIVE;
						r.numbered++;
					}
					else
					{
						ledger.addDropLocked(bossId, s, item, kill, want.get(i).qty, snapFor(bossId, sharedWith, snaps.get(kill)), Drop.SRC_LIVE);
						r.added++;
					}
				}
			}
			return null;
		});
		return r;
	}

	/**
	 * The other bosses' kill counts that matter for the item: the ones that drop it too.
	 */
	private static Map<String, Integer> snapFor(String bossId, List<String> sharedWith, Map<String, Integer> snap)
	{
		if (snap == null || sharedWith == null || sharedWith.size() < 2)
		{
			return null;
		}
		Map<String, Integer> out = new LinkedHashMap<>();
		for (String other : sharedWith)
		{
			Integer k = snap.get(other);
			if (!other.equals(bossId) && k != null && k > 0)
			{
				out.put(other, k);
			}
		}
		return out.isEmpty() ? null : out;
	}
}
