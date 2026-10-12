package com.dropluckledger.plugin;

import com.dropluckledger.capture.BossIndex;
import com.dropluckledger.capture.BossIndex.KcLabel;
import com.dropluckledger.history.HistoryInput;
import com.dropluckledger.ledger.HistoryStage;
import com.dropluckledger.ledger.Ledger;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Collects what the game and RuneLite already know about the player's history, for {@link com.dropluckledger.history.HistoryPlanner}:
 * <ul>
 * <li>the kill counts RuneLite's Chat Commands plugin saved from every kill-count message it has seen,</li>
 * <li>the totals the Loot Tracker plugin saved (items per boss, over the last year, if "Remember loot" is on),</li>
 * <li>whatever the player has opened on screen: the Boss Kill Log and collection log pages (kept in the ledger).</li>
 * </ul>
 * None of these says which kill a drop came on, so history only ever brings in kill counts and owned copies.
 */
public final class HistorySources
{
	public static final String SRC_KC = "RuneLite kill counts";
	public static final String SRC_LOOT = "Loot Tracker";
	public static final String SRC_KILL_LOG = "Boss Kill Log";
	public static final String SRC_COLLECTION = "Collection Log";

	private static final String KILLCOUNT_GROUP = "killcount";
	private static final String LOOT_TRACKER_GROUP = "loottracker";
	private static final String LOOT_PREFIX = "drops_";

	private static final Logger log = LoggerFactory.getLogger(HistorySources.class);

	private HistorySources()
	{
	}

	/**
	 * One saved Loot Tracker total (the part of its format the plugin needs; the rest is ignored).
	 */
	private static final class Aggregate
	{
		String type;
		String name;
		int kills;
		int[] drops;
	}

	/**
	 * Everything available now.  Call on the client thread (item names are looked up in the game).
	 */
	public static HistoryInput collect(Ledger ledger, Game game, Gson gson, BossIndex index)
	{
		HistoryInput in = new HistoryInput();
		readStage(ledger.history(), in);
		readRuneLite(game, gson, index, in);
		return in;
	}

	/**
	 * What has been read off the screen so far.
	 */
	static void readStage(HistoryStage stage, HistoryInput in)
	{
		for (Map.Entry<String, Integer> e : stage.kc.entrySet())
		{
			in.addKc(e.getKey(), e.getValue(), SRC_KILL_LOG);
		}
		for (Map.Entry<String, Map<String, Integer>> page : stage.log.entrySet())
		{
			for (Map.Entry<String, Integer> item : page.getValue().entrySet())
			{
				in.addOwned(null, page.getKey(), item.getKey(), item.getValue(), SRC_COLLECTION);
			}
		}
	}

	/**
	 * What RuneLite's other plugins saved for the profile that is logged in.
	 */
	static void readRuneLite(Game game, Gson gson, BossIndex index, HistoryInput in)
	{
		for (String key : game.savedKeys(KILLCOUNT_GROUP, ""))
		{
			Integer n = game.savedInt(KILLCOUNT_GROUP, key);
			if (n != null && n > 0)
			{
				in.addKc(key, n, SRC_KC);
			}
		}
		for (String key : game.savedKeys(LOOT_TRACKER_GROUP, LOOT_PREFIX))
		{
			Aggregate a = parse(gson, game.savedText(LOOT_TRACKER_GROUP, key));
			if (a == null || a.name == null || a.drops == null || !("NPC".equals(a.type) || "EVENT".equals(a.type)))
			{
				continue;
			}
			String bossId = index.loot(a.name);
			if (bossId == null)
			{
				continue;
			}
			if (a.kills > 0)
			{
				KcLabel label = index.kc(a.name);
				List<KcLabel> labels = index.labels(bossId);
				String name = label != null && label.bossId.equals(bossId) ? a.name : labels.isEmpty() ? null : labels.get(0).label;
				if (name != null)
				{
					in.addKc(name, a.kills, SRC_LOOT);
				}
			}
			for (int i = 0; i + 1 < a.drops.length; i += 2)
			{
				String item = game.itemName(a.drops[i]);
				if (item != null)
				{
					in.addOwned(bossId, null, item, a.drops[i + 1], SRC_LOOT);
				}
			}
		}
	}

	private static Aggregate parse(Gson gson, String json)
	{
		if (json == null || json.isEmpty())
		{
			return null;
		}
		try
		{
			return gson.fromJson(json, Aggregate.class);
		}
		catch (JsonParseException ex)
		{
			log.debug("Skipping a Loot Tracker record that could not be read", ex);
			return null;
		}
	}
}
