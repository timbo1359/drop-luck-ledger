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
		return HistorySources.collect(ledger, game, gson, index);
	}

	private static int kc(HistoryInput in, String label)
	{
		return in.kc.getOrDefault(label, 0);
	}

	@Test
	public void readsTheKillCountsChatCommandsSaved()
	{
		game.save("killcount", "zulrah", "1500").save("killcount", "chambers of xeric challenge mode", "42").save("killcount", "k'ril tsutsaroth", "300");
		HistoryInput in = collect();
		assertEquals(1500, kc(in, "zulrah"));
		assertEquals(42, kc(in, "chambers of xeric challenge mode"));
		assertTrue(in.sources.contains(HistorySources.SRC_KC));
		HistoryPlanner.Plan plan = new HistoryPlanner(ledger, index).plan(in);
		assertEquals(3, plan.kc.size());
		assertTrue(plan.kcSkipped.isEmpty());
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
		game.save("loottracker", "drops_EVENT_Chambers of Xeric Challenge Mode",
			"{\"type\":\"EVENT\",\"name\":\"Chambers of Xeric Challenge Mode\",\"kills\":10,\"drops\":[]}");
		game.save("loottracker", "drops_PLAYER_Someone", "{\"type\":\"PLAYER\",\"name\":\"Someone\",\"kills\":3,\"drops\":[12922,1]}");
		game.save("loottracker", "drops_NPC_Man", "{\"type\":\"NPC\",\"name\":\"Man\",\"kills\":99,\"drops\":[12922,1]}");
		HistoryInput in = collect();
		assertEquals(10, kc(in, "Chambers of Xeric Challenge Mode"));
		assertEquals(1, in.kc.size());
		assertTrue(in.owned.isEmpty());
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
	public void screenReadingsComeFromTheLedger()
	{
		ledger.updateHistory(h ->
		{
			h.kc.put("Zulrah", 700);
			java.util.Map<String, Integer> page = new java.util.LinkedHashMap<>();
			page.put("Tanzanite fang", 1);
			h.log.put("Zulrah", page);
		});
		HistoryInput in = collect();
		assertEquals(700, kc(in, "Zulrah"));
		assertEquals(1, in.owned.size());
		assertTrue(in.sources.contains(HistorySources.SRC_KILL_LOG));
		assertTrue(in.sources.contains(HistorySources.SRC_COLLECTION));
	}

	@Test
	public void sourcesAgreeAndTheLargestCountWins()
	{
		game.save("killcount", "zulrah", "1500");
		game.save("loottracker", "drops_NPC_Zulrah", "{\"type\":\"NPC\",\"name\":\"Zulrah\",\"kills\":400,\"drops\":[12922,1]}");
		ledger.updateHistory(h -> h.kc.put("Zulrah", 1490));
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
}
