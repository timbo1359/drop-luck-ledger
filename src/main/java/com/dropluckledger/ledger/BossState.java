package com.dropluckledger.ledger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the ledger knows about one boss.  The first six fields are the same as the web app's backup format,
 * the rest belong to the plugin (they are ignored by the web app).
 */
public final class BossState
{
	public int kc;
	public List<Period> segs = new ArrayList<>();
	public List<Drop> drops = new ArrayList<>();
	public List<String> track = new ArrayList<>();
	public List<String> hide = new ArrayList<>();
	public long u;

	/**
	 * Plugin only: the last kill count the game showed for each kill-count label of the boss (for example the normal and
	 * the awakened count of a Desert Treasure II boss).  The ledger kill count is their sum plus any kills added by hand.
	 */
	public Map<String, Integer> labelKc;
	/**
	 * Plugin only: kills counted from a loot event before the game's kill-count message for them arrived.
	 */
	public Integer prov;
	/**
	 * Plugin only: ids of the drops recorded on those kills.
	 */
	public List<String> provDrops;
	/**
	 * Plugin only: the first kill the plugin counted from the game itself (null until it has counted one).  Kills before it came
	 * from history or were set by hand, so what the game reveals per kill (whose loot it was, how many brothers fell) is unknown
	 * for them; settings of that kind apply to them alone.
	 */
	public Integer liveFrom;
	/**
	 * Plugin only: for each setting the game reveals (see {@code Boss.reads}), the first kill the plugin read it for.  The kills
	 * before it are the ones the player's own value applies to; a setting missing here was never read, so it applies to every kill.
	 */
	public Map<String, Integer> seen;

	public BossState()
	{
		segs.add(new Period(1, new LinkedHashMap<>()));
	}

	public int maxDropKc()
	{
		int m = 0;
		for (Drop d : drops)
		{
			if (d.kc != null && d.kc > m)
			{
				m = d.kc;
			}
		}
		return m;
	}

	public int copies(String item)
	{
		int n = 0;
		for (Drop d : drops)
		{
			if (d.item.equals(item))
			{
				n++;
			}
		}
		return n;
	}

	/**
	 * Copies of an item that came from a history import and have no kill number.
	 */
	public int importedUnknown(String item)
	{
		int n = 0;
		for (Drop d : drops)
		{
			if (d.item.equals(item) && d.kc == null && Drop.SRC_IMPORT.equals(d.src))
			{
				n++;
			}
		}
		return n;
	}

	public int provisional()
	{
		return prov == null ? 0 : prov;
	}

	public Map<String, Integer> labels()
	{
		if (labelKc == null)
		{
			labelKc = new LinkedHashMap<>();
		}
		return labelKc;
	}

	public int labelSum(String except)
	{
		int s = 0;
		if (labelKc != null)
		{
			for (Map.Entry<String, Integer> e : labelKc.entrySet())
			{
				if (!e.getKey().equals(except))
				{
					s += e.getValue();
				}
			}
		}
		return s;
	}

	/**
	 * The first kill the plugin read this setting for, or null when it never has.
	 */
	public Integer seenFrom(String setting)
	{
		return seen == null ? null : seen.get(setting);
	}

	public Period lastPeriod()
	{
		return segs.get(segs.size() - 1);
	}

	/**
	 * The settings in force at a kill number (the latest period starting at or before it).
	 */
	public Map<String, Object> paramsAt(int killNo)
	{
		Period best = segs.get(0);
		for (Period p : segs)
		{
			if (p.from <= killNo && p.from >= best.from)
			{
				best = p;
			}
		}
		return best.params;
	}

	public BossState copy()
	{
		BossState b = new BossState();
		b.kc = kc;
		b.segs = new ArrayList<>();
		for (Period p : segs)
		{
			b.segs.add(p.copy());
		}
		b.drops = new ArrayList<>();
		for (Drop d : drops)
		{
			b.drops.add(d.copy());
		}
		b.track = new ArrayList<>(track);
		b.hide = new ArrayList<>(hide);
		b.u = u;
		b.labelKc = labelKc == null ? null : new LinkedHashMap<>(labelKc);
		b.prov = prov;
		b.provDrops = provDrops == null ? null : new ArrayList<>(provDrops);
		b.liveFrom = liveFrom;
		b.seen = seen == null ? null : new LinkedHashMap<>(seen);
		return b;
	}
}
