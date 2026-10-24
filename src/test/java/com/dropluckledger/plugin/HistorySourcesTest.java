package com.dropluckledger.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.dropluckledger.TestData;
import com.dropluckledger.capture.BossIndex;
import com.dropluckledger.history.HistoryInput;
import com.dropluckledger.history.HistoryPlanner;
import com.dropluckledger.ledger.Ledger;
import com.google.gson.Gson;
import org.junit.Before;
import org.junit.Test;

public class HistorySourcesTest
{
	private final BossIndex index = new BossIndex(TestData.get());
	private final Gson gson = new Gson();
	private FakeGame game;
	private Ledger ledger;

	@Before
	public void setUp()
	{
		game = new FakeGame()
			.addItem(12922, "Tanzanite fang")
			.addItem(12927, "Magic fang")
			.addItem(995, "Coins")
			.addItem(22106, "Vorkath's head");
		ledger = new Ledger(TestData.get());
	}

	private HistoryInput collect()
	{
		return HistorySources.collect(game, gson, index);
	}

	private static int kc(HistoryInput in, String label)
	{
		return in.kc.getOrDefault(label, 0);
	}

	@Test
	public void readsTheKillCountsChatCommandsSaved()
	{
		game.save("killcount", "zulrah", "1500").save("killcount", "corrupted gauntlet", "42").save("killcount", "k'ril tsutsaroth", "300")
			.save("killcount", "chambers of xeric", "70").save("killcount", "royal titans", "12");
		HistoryInput in = collect();
		assertEquals(1500, kc(in, "zulrah"));
		assertEquals(42, kc(in, "corrupted gauntlet"));
		assertTrue(in.sources.contains(HistorySources.SRC_KC));
		HistoryPlanner.Plan plan = new HistoryPlanner(ledger, index).plan(in);
		assertEquals(4, plan.kc.size());
		// a boss the plugin leaves out is not used
		assertEquals(java.util.Collections.singletonList("royal titans 12"), plan.kcSkipped);
	}

	@Test
	public void ignoresZeroAndMissingCounts()
	{
		game.save("killcount", "zulrah", "0");
		assertTrue(collect().isEmpty());
	}

	@Test
	public void noProfileMeansNothingToRead()
	{
		game.hasProfile = false;
		game.save("killcount", "zulrah", "5");
		assertTrue(collect().isEmpty());
	}

	@Test
	public void readsLootTrackerTotals()
	{
		game.save("loottracker", "drops_NPC_Zulrah",
			"{\"type\":\"NPC\",\"name\":\"Zulrah\",\"kills\":800,\"first\":\"2025-01-01T00:00:00Z\",\"last\":\"2026-09-01T00:00:00Z\",\"drops\":[12922,2,12927,1,995,50000]}");
		HistoryInput in = collect();
		assertEquals(800, kc(in, "Zulrah"));
		assertEquals(3, in.owned.size());
		HistoryPlanner.Plan plan = new HistoryPlanner(ledger, index).plan(in);
		assertEquals(800, plan.kc.get(0).to);
		int fangs = 0;
		for (HistoryPlanner.ItemRow r : plan.items)
		{
			if (r.item.equals("Tanzanite fang"))
			{
				fangs = r.add;
				assertEquals("zulrah", r.bossId);
			}
		}
		assertEquals(2, fangs);
		assertTrue(plan.itemsMissing.contains("Coins") || plan.items.stream().noneMatch(r -> r.item.equals("Coins")));
	}

	@Test
	public void eventLootAndOtherRecordTypes()
	{
		game.save("loottracker", "drops_EVENT_Lunar Chest", "{\"type\":\"EVENT\",\"name\":\"Lunar Chest\",\"kills\":10,\"drops\":[]}");
		game.save("loottracker", "drops_EVENT_Chambers of Xeric", "{\"type\":\"EVENT\",\"name\":\"Chambers of Xeric\",\"kills\":30,\"drops\":[12922,1]}");
		game.save("loottracker", "drops_PLAYER_Someone", "{\"type\":\"PLAYER\",\"name\":\"Someone\",\"kills\":3,\"drops\":[12922,1]}");
		game.save("loottracker", "drops_NPC_Man", "{\"type\":\"NPC\",\"name\":\"Man\",\"kills\":99,\"drops\":[12922,1]}");
		HistoryInput in = collect();
		assertEquals(10, kc(in, "Lunar Chest"));
		assertEquals(30, kc(in, "Chambers of Xeric"));
		assertEquals(2, in.kc.size());
		// a raid's chest is an event too; the tanzanite fang is not a raid item, so nothing is owned
		assertEquals(1, in.owned.size());
		assertEquals("chambers-of-xeric", in.owned.get(0).bossId);
	}

	@Test
	public void lootNamesThatAreNotKillCountLabelsUseTheBossLabel()
	{
		// the Loot Tracker calls the Grotesque Guardians "Dusk"; the kill count message calls them something else
		game.save("loottracker", "drops_NPC_Dusk", "{\"type\":\"NPC\",\"name\":\"Dusk\",\"kills\":25,\"drops\":[]}");
		HistoryInput in = collect();
		HistoryPlanner.Plan plan = new HistoryPlanner(ledger, index).plan(in);
		assertEquals(1, plan.kc.size());
		assertEquals("grotesque-guardians", plan.kc.get(0).bossId);
		assertEquals(25, plan.kc.get(0).to);
		assertTrue(plan.kcSkipped.toString(), plan.kcSkipped.isEmpty());
	}

	@Test
	public void brokenRecordsAreSkipped()
	{
		game.save("loottracker", "drops_NPC_Zulrah", "{not json");
		game.save("loottracker", "drops_NPC_Vorkath", "[1,2,3]");
		game.save("loottracker", "drops_NPC_Nex", "{\"type\":\"NPC\",\"name\":\"Nex\"}");
		game.save("loottracker", "drops_NPC_Kraken", "");
		assertTrue(collect().isEmpty());
	}

	@Test
	public void sourcesAgreeAndTheLargestCountWins()
	{
		game.save("killcount", "zulrah", "1500");
		game.save("loottracker", "drops_NPC_Zulrah", "{\"type\":\"NPC\",\"name\":\"Zulrah\",\"kills\":400,\"drops\":[12922,1]}");
		HistoryPlanner.Plan plan = new HistoryPlanner(ledger, index).plan(collect());
		assertEquals(1500, plan.kc.get(0).to);
	}

	@Test
	public void runningItAfterImportingAddsNothing()
	{
		game.save("killcount", "zulrah", "1500");
		game.save("loottracker", "drops_NPC_Zulrah", "{\"type\":\"NPC\",\"name\":\"Zulrah\",\"kills\":1500,\"drops\":[12922,2]}");
		HistoryPlanner planner = new HistoryPlanner(ledger, index);
		HistoryPlanner.Plan first = planner.plan(collect());
		assertFalse(first.isEmpty());
		planner.apply(first);
		assertEquals(1500, ledger.kc("zulrah"));
		assertEquals(2, ledger.copyOf("zulrah").copies("Tanzanite fang"));
		assertTrue(planner.plan(collect()).isEmpty());
	}
	@Test
	public void recordsOfOneBossAddUpAndBodyguardsAreNoKills()
	{
		game.addItem(12601, "Ring of the gods").addItem(11818, "Godsword shard 1");
		game.save("loottracker", "drops_NPC_Vet'ion", "{\"type\":\"NPC\",\"name\":\"Vet'ion\",\"kills\":60,\"drops\":[12601,2]}");
		game.save("loottracker", "drops_NPC_Vet'ion Reborn", "{\"type\":\"NPC\",\"name\":\"Vet'ion Reborn\",\"kills\":40,\"drops\":[12601,3]}");
		game.save("loottracker", "drops_NPC_General Graardor",
			"{\"type\":\"NPC\",\"name\":\"General Graardor\",\"kills\":100,\"drops\":[11818,1]}");
		game.save("loottracker", "drops_NPC_Sergeant Strongstack",
			"{\"type\":\"NPC\",\"name\":\"Sergeant Strongstack\",\"kills\":300,\"drops\":[11818,1]}");
		HistoryInput in = collect();
		assertEquals(100, kc(in, "Vet'ion"));
		assertEquals(100, kc(in, "General Graardor"));
		HistoryPlanner.Plan plan = new HistoryPlanner(ledger, index).plan(in);
		int rings = 0;
		int shards = 0;
		for (HistoryPlanner.ItemRow r : plan.items)
		{
			if (r.item.equals("Ring of the gods"))
			{
				rings = r.add;
				assertEquals("vetion", r.bossId);
			}
			if (r.item.equals("Godsword shard 1"))
			{
				shards = r.add;
				assertEquals("general-graardor", r.bossId);
			}
		}
		assertEquals(5, rings);
		assertEquals(2, shards);
	}

	@Test
	public void itemsThePluginLeavesOutAreNotAdded()
	{
		HistoryInput in = new HistoryInput();
		in.addOwned(null, "The Hueycoatl", "Tooth half of key", 2, "Collection Log");
		in.addOwned(null, "The Hueycoatl", "Dragon hunter wand", 1, "Collection Log");
		in.addOwned(null, "Royal Titans", "Giantsoul amulet (uncharged)", 4, "Collection Log");
		HistoryPlanner.Plan plan = new HistoryPlanner(ledger, index).plan(in);
		assertTrue(plan.itemsMissing.toString(), plan.itemsMissing.contains("Tooth half of key"));
		assertTrue(plan.itemsMissing.toString(), plan.itemsMissing.contains("Giantsoul amulet (uncharged)"));
		int wands = 0;
		for (HistoryPlanner.ItemRow r : plan.items)
		{
			assertFalse(r.item, r.item.startsWith("Tooth half") || r.item.startsWith("Giantsoul"));
			if (r.item.equals("Dragon hunter wand"))
			{
				wands = r.add;
			}
		}
		assertEquals(1, wands);
	}
}
