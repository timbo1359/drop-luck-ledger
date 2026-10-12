package com.dropluckledger.ledger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One copy of an item the player received.
 */
public final class Drop
{
	/**
	 * Came from the collection log or loot tracker history: the kill number is unknown.
	 */
	public static final String SRC_IMPORT = "import";
	/**
	 * Seen in game while the plugin was running.
	 */
	public static final String SRC_LIVE = "live";
	/**
	 * Typed in by the player.
	 */
	public static final String SRC_MANUAL = "manual";

	public String id;
	public String item;
	/**
	 * Ledger kill number it came on, null when unknown.
	 */
	public Integer kc;
	public int qty = 1;
	/**
	 * Epoch milliseconds when it was recorded.
	 */
	public long t;
	/**
	 * For items several bosses drop: the other bosses' kill counts when this one dropped.
	 */
	public Map<String, Integer> snap;
	public String src;

	public Drop copy()
	{
		Drop d = new Drop();
		d.id = id;
		d.item = item;
		d.kc = kc;
		d.qty = qty;
		d.t = t;
		d.snap = snap == null ? null : new LinkedHashMap<>(snap);
		d.src = src;
		return d;
	}
}
