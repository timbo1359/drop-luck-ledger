package com.dropluckledger.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.dropluckledger.TestData;
import com.dropluckledger.capture.BossIndex;
import com.dropluckledger.capture.Capture;
import com.dropluckledger.engine.Data;
import com.dropluckledger.history.HistoryPlanner.ItemRow;
import com.dropluckledger.history.HistoryPlanner.KcRow;
import com.dropluckledger.history.HistoryPlanner.Plan;
import com.dropluckledger.ledger.BossState;
import com.dropluckledger.ledger.Drop;
import com.dropluckledger.ledger.Ledger;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;

public class HistoryPlannerTest
{
	private final Data data = TestData.get();
	private final BossIndex index = new BossIndex(data);
	private Ledger ledger;
	private HistoryPlanner planner;

	@Before
	public void setUp()
	{
		ledger = new Ledger(data, () -> 5_000L);
		planner = new HistoryPlanner(ledger, index);
	}

	private static KcRow kcRow(Plan p, String id)
	{
		for (KcRow r : p.kc)
		{
			if (r.bossId.equals(id))
			{
				return r;
			}
		}
		return null;
	}

	private static ItemRow itemRow(Plan p, String id, String item)
	{
		for (ItemRow r : p.items)
		{
			if (r.bossId.equals(id) && r.item.equals(item))
			{
				return r;
			}
		}
		return null;
	}

	@Test
	public void killCountsFromTheBossKillLog()
	{
		HistoryInput in = new HistoryInput();
		in.addKc("Zulrah", 1234, "Boss Kill Log");
		in.addKc("Barrows Chests", 280, "Boss Kill Log");
		in.addKc("Corporeal Beast", 7, "Boss Kill Log");
		in.addKc("Goblin", 3, "Boss Kill Log");
		in.addKc("The Nightmare", 15, "Boss Kill Log");
		Plan plan = planner.plan(in);
		assertEquals(4, plan.kc.size());
		assertEquals(1, plan.kcSkipped.size());
		assertEquals(1234, kcRow(plan, "zulrah").to);
		assertEquals(280, kcRow(plan, "barrows").to);
		assertEquals(15, kcRow(plan, "the-nightmare").to);
		assertEquals(4, plan.kcToApply());
		int[] done = planner.apply(plan);
		assertEquals(4, done[0]);
		assertEquals(1234, ledger.kc("zulrah"));
		assertEquals(Integer.valueOf(1234), ledger.copyOf("zulrah").labelKc.get("Zulrah"));
		assertEquals(280, ledger.kc("barrows"));
		assertEquals(Integer.valueOf(280), ledger.copyOf("barrows").labelKc.get("Barrows Chest"));
	}

	@Test
	public void modesOfOneBossAreAddedUpAndLaidOutInBlocks()
	{
		HistoryInput in = new HistoryInput();
		in.addKc("Chambers of Xeric", 50, "Boss Kill Log");
		in.addKc("Chambers of Xeric Challenge Mode", 12, "Boss Kill Log");
		in.addKc("Duke Sucellus", 100, "Boss Kill Log");
		in.addKc("Awakened Duke Sucellus", 30, "Boss Kill Log");
		Plan plan = planner.plan(in);
		assertEquals(62, kcRow(plan, "chambers-of-xeric").to);
		assertEquals(130, kcRow(plan, "duke-sucellus").to);
		planner.apply(plan);
		BossState c = ledger.copyOf("chambers-of-xeric");
		assertEquals(62, c.kc);
		assertEquals(2, c.segs.size());
		assertEquals(1, c.segs.get(0).from);
		assertEquals(0, ((Number) c.paramsAt(50).get("cm")).intValue());
		assertEquals(51, c.segs.get(1).from);
		assertEquals(1, ((Number) c.paramsAt(62).get("cm")).intValue());
		BossState d = ledger.copyOf("duke-sucellus");
		assertEquals(101, d.segs.get(1).from);
		assertEquals(1, ((Number) d.paramsAt(130).get("awakened")).intValue());
		assertEquals(0, ((Number) d.paramsAt(100).get("awakened")).intValue());
	}

	@Test
	public void applyingTwiceChangesNothing()
	{
		HistoryInput in = new HistoryInput();
		in.addKc("Zulrah", 500, "Boss Kill Log");
		in.addOwned(null, "Zulrah", "Tanzanite fang", 2, "Collection log");
		in.addOwned(null, "Zulrah", "Pet snakeling", 1, "Collection log");
		planner.apply(planner.plan(in));
		assertEquals(3, ledger.copyOf("zulrah").drops.size());
		Plan again = planner.plan(in);
		assertFalse(kcRow(again, "zulrah").on);
		assertEquals(0, again.copiesToAdd());
		assertTrue(again.isEmpty());
		int[] done = planner.apply(again);
		assertEquals(0, done[0]);
		assertEquals(0, done[1]);
		assertEquals(3, ledger.copyOf("zulrah").drops.size());
	}

	@Test
	public void ownedCopiesComeInWithoutAKillNumber()
	{
		HistoryInput in = new HistoryInput();
		in.addOwned(null, "Zulrah", "tanzanite FANG", 2, "Collection log");
		in.addOwned(null, "Zulrah", "Coins", 100, "Collection log");
		Plan plan = planner.plan(in);
		ItemRow r = itemRow(plan, "zulrah", "Tanzanite fang");
		assertNotNull(r);
		assertEquals(2, r.add);
		assertFalse(r.assumed);
		assertEquals(Arrays.asList("Coins"), plan.itemsMissing);
		planner.apply(plan);
		BossState z = ledger.copyOf("zulrah");
		assertEquals(2, z.copies("Tanzanite fang"));
		for (Drop d : z.drops)
		{
			assertNull(d.kc);
			assertEquals(Drop.SRC_IMPORT, d.src);
		}
	}

	@Test
	public void copiesTheLedgerAlreadyHasAreNotAddedAgain()
	{
		Capture capture = new Capture(ledger, index);
		capture.onKillCount("Zulrah", 100, 0);
		capture.onLoot(new Capture.Loot("Zulrah", 1, Arrays.asList(new Capture.Stack("Tanzanite fang", 1)), null, 2));
		assertEquals(1, ledger.copyOf("zulrah").copies("Tanzanite fang"));
		HistoryInput in = new HistoryInput();
		in.addOwned(null, "Zulrah", "Tanzanite fang", 3, "Collection log");
		Plan plan = planner.plan(in);
		ItemRow r = itemRow(plan, "zulrah", "Tanzanite fang");
		assertEquals(3, r.want);
		assertEquals(1, r.have);
		assertEquals(2, r.add);
		planner.apply(plan);
		BossState z = ledger.copyOf("zulrah");
		assertEquals(3, z.copies("Tanzanite fang"));
		// the live copy keeps its kill number
		assertEquals(Integer.valueOf(100), z.drops.get(0).kc);
	}

	@Test
	public void lootTrackerCopiesNameTheBossAndOverrideTheCollectionLog()
	{
		HistoryInput in = new HistoryInput();
		in.addOwned("zulrah", null, "Tanzanite fang", 3, "Loot Tracker");
		in.addOwned(null, "Zulrah", "Tanzanite fang", 2, "Collection log"); // log says 2, loot tracker 3: the larger count wins
		Plan plan = planner.plan(in);
		assertEquals(1, plan.items.size());
		assertEquals(3, itemRow(plan, "zulrah", "Tanzanite fang").want);
		HistoryInput in2 = new HistoryInput();
		in2.addOwned("zulrah", null, "Tanzanite fang", 3, "Loot Tracker");
		in2.addOwned(null, "Zulrah", "Tanzanite fang", 4, "Collection log"); // one more than the loot tracker saw
		Plan plan2 = planner.plan(in2);
		assertEquals(4, itemRow(plan2, "zulrah", "Tanzanite fang").want);
	}

	@Test
	public void sharedItemsGoToTheBossTheCollectionLogPageNames()
	{
		HistoryInput in = new HistoryInput();
		in.addKc("Callisto", 400, "Boss Kill Log");
		in.addKc("Venenatis", 900, "Boss Kill Log");
		in.addOwned(null, "Venenatis and Spindel", "Dragon pickaxe", 1, "Collection log");
		in.addOwned(null, "Callisto and Artio", "Callisto cub", 1, "Collection log");
		Plan plan = planner.plan(in);
		ItemRow pick = itemRow(plan, "venenatis", "Dragon pickaxe");
		assertNotNull(pick); // the page names Venenatis and Spindel: it goes to the one with more kills, and is flagged
		assertTrue(pick.assumed);
		assertNull(itemRow(plan, "spindel", "Dragon pickaxe"));
		// the page names two bosses: it goes to the one with more kills and is flagged
		ItemRow cub = itemRow(plan, "callisto", "Callisto cub");
		ItemRow cubArtio = itemRow(plan, "artio", "Callisto cub");
		assertTrue(cub != null || cubArtio != null);
		assertTrue((cub != null ? cub : cubArtio).assumed);
	}

	@Test
	public void absurdCountsAreCapped()
	{
		HistoryInput in = new HistoryInput();
		in.addOwned(null, "Zulrah", "Magic fang", 5000, "Collection log");
		Plan plan = planner.plan(in);
		assertEquals(HistoryPlanner.MAX_COPIES, itemRow(plan, "zulrah", "Magic fang").add);
	}

	@Test
	public void aStackOfBoltRacksIsNotThreeThousandDrops()
	{
		// the Collection Log and the Loot Tracker keep the running total of the stack, not the number of chests it came from
		HistoryInput in = new HistoryInput();
		in.addKc("Barrows Chests", 1000, "Boss Kill Log");
		in.addOwned(null, "Barrows Chests", "Bolt rack", 3750, "Collection log");
		Plan plan = planner.plan(in);
		ItemRow r = itemRow(plan, "barrows", "Bolt rack");
		assertNotNull(r);
		assertEquals(100, r.want);
		assertEquals(3750, r.quantity);
		assertTrue(r.estimated());
		assertEquals(100, r.add);
		planner.apply(plan);
		assertEquals(100, ledger.copyOf("barrows").copies("Bolt rack"));
		// and the same total from the Loot Tracker, which names the boss
		HistoryInput exact = new HistoryInput();
		exact.addOwned("barrows", null, "Bolt rack", 3750, "Loot Tracker");
		ItemRow e = itemRow(planner.plan(exact), "barrows", "Bolt rack");
		assertEquals(100, e.want);
		assertEquals(3750, e.quantity);
		assertEquals(0, e.add); // already in the ledger from the first import
	}

	@Test
	public void anEarlierImportThatCountedAStackAsDropsIsCorrected()
	{
		// the first version of the importer took 3,750 bolt racks as 200 drops (its cap)
		ledger.setKc("barrows", 1000);
		ledger.logDrop("barrows", "Bolt rack", 50, 1, null, Drop.SRC_MANUAL);
		ledger.addOwned("barrows", "Bolt rack", 200);
		HistoryInput in = new HistoryInput();
		in.addOwned(null, "Barrows Chests", "Bolt rack", 3750, "Collection log");
		Plan plan = planner.plan(in);
		ItemRow r = itemRow(plan, "barrows", "Bolt rack");
		assertEquals(100, r.want);
		assertEquals(201, r.have);
		assertEquals(0, r.add);
		assertEquals(101, r.remove);
		assertTrue(r.on);
		assertEquals(101, plan.copiesToRemove());
		assertFalse(plan.isEmpty());
		int[] done = planner.apply(plan);
		assertEquals(0, done[1]);
		assertEquals(101, done[2]);
		BossState b = ledger.copyOf("barrows");
		assertEquals(100, b.copies("Bolt rack"));
		// the copy that was logged by hand, with its kill number, is still there
		assertEquals(1, b.drops.stream().filter(d -> d.kc != null && d.item.equals("Bolt rack")).count());
		// and a second run has nothing left to do
		assertTrue(planner.plan(in).isEmpty());
	}

	@Test
	public void copiesAreNeverTakenBackForItemsThatDropOneAtATime()
	{
		ledger.addOwned("zulrah", "Tanzanite fang", 5);
		HistoryInput in = new HistoryInput();
		in.addOwned(null, "Zulrah", "Tanzanite fang", 2, "Collection log");
		Plan plan = planner.plan(in);
		assertEquals(0, itemRow(plan, "zulrah", "Tanzanite fang").remove);
		assertTrue(plan.isEmpty());
		planner.apply(plan);
		assertEquals(5, ledger.copyOf("zulrah").copies("Tanzanite fang"));
	}

	@Test
	public void itemsThatDropOneAtATimeAreNotEstimated()
	{
		HistoryInput in = new HistoryInput();
		in.addOwned(null, "Zulrah", "Tanzanite fang", 2, "Collection log");
		ItemRow r = itemRow(planner.plan(in), "zulrah", "Tanzanite fang");
		assertEquals(2, r.want);
		assertEquals(0, r.quantity);
		assertFalse(r.estimated());
	}

	@Test
	public void aBossWithManyKillsTakesMoreThanTheUsualCap()
	{
		HistoryInput in = new HistoryInput();
		in.addKc("Zulrah", 1000, "Boss Kill Log");
		in.addOwned(null, "Zulrah", "Magic fang", 500, "Collection log");
		Plan plan = planner.plan(in);
		assertEquals(500, itemRow(plan, "zulrah", "Magic fang").add);
		planner.apply(plan);
		assertEquals(500, ledger.copyOf("zulrah").copies("Magic fang"));
	}

	@Test
	public void killCountNeverGoesDown()
	{
		ledger.setKc("zulrah", 2000);
		HistoryInput in = new HistoryInput();
		in.addKc("Zulrah", 1500, "Boss Kill Log");
		Plan plan = planner.plan(in);
		assertFalse(kcRow(plan, "zulrah").on);
		planner.apply(plan);
		assertEquals(2000, ledger.kc("zulrah"));
	}

	@Test
	public void importThenLiveKillsContinueFromTheImportedCount()
	{
		HistoryInput in = new HistoryInput();
		in.addKc("Zulrah", 1234, "Boss Kill Log");
		planner.apply(planner.plan(in));
		Capture capture = new Capture(ledger, index);
		capture.onKillCount("Zulrah", 1235, 10);
		capture.onLoot(new Capture.Loot("Zulrah", 1, Arrays.asList(new Capture.Stack("Magic fang", 1)), null, 12));
		BossState z = ledger.copyOf("zulrah");
		assertEquals(1235, z.kc);
		assertEquals(Integer.valueOf(1235), z.drops.get(0).kc);
	}
}
