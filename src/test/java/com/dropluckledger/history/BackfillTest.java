package com.dropluckledger.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.dropluckledger.TestData;
import com.dropluckledger.capture.BossIndex;
import com.dropluckledger.engine.Data;
import com.dropluckledger.ledger.BossState;
import com.dropluckledger.ledger.Drop;
import com.dropluckledger.ledger.Ledger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/**
 * An item tracked late gets its earlier copies from the drop log.
 */
public class BackfillTest
{
	private final Data data = TestData.get();
	private final BossIndex index = new BossIndex(data);
	private long now = 10_000L;
	private Ledger ledger;

	@Before
	public void setUp()
	{
		ledger = new Ledger(data, () -> now);
	}

	private static LoggedLoot loot(String boss, int kill, long t, String... items)
	{
		List<LoggedLoot.Item> l = new ArrayList<>();
		for (String i : items)
		{
			l.add(new LoggedLoot.Item(i, i.equals("Zulrah's scales") ? 150 : 1));
		}
		return new LoggedLoot(t, boss, kill, l, null);
	}

	private List<Integer> kills(String boss, String item)
	{
		List<Integer> out = new ArrayList<>();
		for (Drop d : ledger.copyOf(boss).drops)
		{
			if (d.item.equals(item))
			{
				out.add(d.kc);
			}
		}
		return out;
	}

	@Test
	public void theCopiesComeInAtTheKillsTheyCameOn()
	{
		ledger.setKc("zulrah", 200);
		List<LoggedLoot> log = Arrays.asList(
			loot("zulrah", 10, 1_000, "Coins", "Zulrah's scales"),
			loot("zulrah", 30, 2_000, "Coins", "Magic fang"),
			loot("vorkath", 31, 2_100, "Magic fang"), // another boss's log is not this one's
			loot("zulrah", 70, 3_000, "Magic fang", "Uncut onyx"));
		ledger.setTracked("zulrah", "Zulrah's scales", true);
		Backfill.Result r = Backfill.apply(ledger, index, "zulrah", "Magic fang", log);
		assertEquals(2, r.added);
		assertEquals(0, r.numbered);
		assertEquals(Arrays.asList(30, 70), kills("zulrah", "Magic fang"));
		for (Drop d : ledger.copyOf("zulrah").drops)
		{
			assertEquals(Drop.SRC_LIVE, d.src);
		}
		// a stack is one drop, with its quantity
		Backfill.apply(ledger, index, "zulrah", "Zulrah's scales", log);
		BossState z = ledger.copyOf("zulrah");
		assertEquals(1, z.copies("Zulrah's scales"));
		for (Drop d : z.drops)
		{
			if (d.item.equals("Zulrah's scales"))
			{
				assertEquals(150, d.qty);
			}
		}
		// looking again adds nothing
		assertTrue(Backfill.apply(ledger, index, "zulrah", "Magic fang", log).isEmpty());
		assertEquals(200, ledger.kc("zulrah"));
	}

	@Test
	public void aCopyTheLedgerHasAtThatKillIsNotAddedTwice()
	{
		ledger.setKc("zulrah", 100);
		ledger.logDrop("zulrah", "Magic fang", 30, 1, null, Drop.SRC_LIVE);
		List<LoggedLoot> log = Arrays.asList(loot("zulrah", 30, 2_000, "Magic fang"),
			// two in one loot are two drops
			loot("zulrah", 60, 3_000, "Magic fang", "Magic fang"));
		Backfill.Result r = Backfill.apply(ledger, index, "zulrah", "Magic fang", log);
		assertEquals(2, r.added);
		assertEquals(Arrays.asList(30, 60, 60), kills("zulrah", "Magic fang"));
	}

	@Test
	public void aCopyFromGameHistoryTakesTheKillNumberWhenItCountedThatLoot()
	{
		ledger.setKc("zulrah", 100);
		// the collection log was read (and its copy added) after the fang dropped: that copy is this one
		now = 5_000L;
		ledger.addOwned("zulrah", "Magic fang", 1);
		Backfill.Result r = Backfill.apply(ledger, index, "zulrah", "Magic fang", Arrays.asList(loot("zulrah", 40, 3_000, "Magic fang")));
		assertEquals(0, r.added);
		assertEquals(1, r.numbered);
		assertEquals(Arrays.asList(40), kills("zulrah", "Magic fang"));
		assertEquals(Drop.SRC_LIVE, ledger.copyOf("zulrah").drops.get(0).src);
	}

	@Test
	public void aCopyFromGameHistoryOlderThanTheLootIsAnotherCopy()
	{
		ledger.setKc("zulrah", 100);
		now = 1_000L;
		ledger.addOwned("zulrah", "Magic fang", 1);
		Backfill.Result r = Backfill.apply(ledger, index, "zulrah", "Magic fang", Arrays.asList(loot("zulrah", 40, 3_000, "Magic fang")));
		assertEquals(1, r.added);
		assertEquals(0, r.numbered);
		List<Integer> k = kills("zulrah", "Magic fang");
		assertEquals(2, k.size());
		assertNull(k.get(0));
		assertEquals(Integer.valueOf(40), k.get(1));
	}

	@Test
	public void lootPastTheKillCountBelongsToAnotherLedger()
	{
		// the ledger was replaced by an older backup: the log runs further than its kills
		ledger.setKc("zulrah", 50);
		Backfill.Result r = Backfill.apply(ledger, index, "zulrah", "Magic fang",
			Arrays.asList(loot("zulrah", 40, 1_000, "Magic fang"), loot("zulrah", 80, 2_000, "Magic fang")));
		assertEquals(1, r.added);
		assertEquals(Arrays.asList(40), kills("zulrah", "Magic fang"));
		assertEquals(50, ledger.kc("zulrah"));
	}

	@Test
	public void aSharedItemKeepsTheOtherBossesKillCountsThatMatter()
	{
		ledger.setKc("vardorvis", 100);
		Map<String, Integer> snap = new LinkedHashMap<>();
		snap.put("the-whisperer", 30);
		snap.put("zulrah", 500); // does not drop it
		List<LoggedLoot> log = Arrays.asList(new LoggedLoot(1_000, "vardorvis", 44,
			Arrays.asList(new LoggedLoot.Item("Virtus mask", 1)), snap));
		Backfill.apply(ledger, index, "vardorvis", "Virtus mask", log);
		Drop d = ledger.copyOf("vardorvis").drops.get(0);
		assertEquals(Integer.valueOf(44), d.kc);
		assertEquals(1, d.snap.size());
		assertEquals(Integer.valueOf(30), d.snap.get("the-whisperer"));
	}
}
