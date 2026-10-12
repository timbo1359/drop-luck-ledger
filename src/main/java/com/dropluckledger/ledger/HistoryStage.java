package com.dropluckledger.ledger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the plugin has read from the game's own records (Boss Kill Log, collection log, hiscores) and not yet turned into
 * ledger entries.  Kept in the ledger file so a page read one day is still there the next.
 */
public final class HistoryStage
{
	/**
	 * Activity name as the game or hiscores show it, to the kill count.
	 */
	public final Map<String, Integer> kc = new LinkedHashMap<>();
	/**
	 * Collection log page title, to item name, to number of copies.
	 */
	public final Map<String, Map<String, Integer>> log = new LinkedHashMap<>();
	public long kcTime;
	public long logTime;

	public boolean isEmpty()
	{
		return kc.isEmpty() && log.isEmpty();
	}

	public HistoryStage copy()
	{
		HistoryStage h = new HistoryStage();
		h.kc.putAll(kc);
		for (Map.Entry<String, Map<String, Integer>> e : log.entrySet())
		{
			h.log.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
		}
		h.kcTime = kcTime;
		h.logTime = logTime;
		return h;
	}
}
