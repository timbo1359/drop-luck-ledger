package com.dropluckledger.ledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.dropluckledger.TestData;
import com.dropluckledger.engine.Data;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class LedgerMergeTest
{
	private final Data data = TestData.get();

	private Ledger ledger()
	{
		return new Ledger(data, () -> 1_700_000_000_000L);
	}

	@Test
	public void aBossTheLedgerLacksComesOverWhole()
	{
		Ledger mine = ledger();
		mine.setKc("zulrah", 100);
		Ledger backup = ledger();
		backup.setKc("vorkath", 250);
		backup.logDrop("vorkath", "Dragonbone necklace", 90, 1, null, Drop.SRC_MANUAL);
		int added = mine.mergeFrom(backup);
		assertEquals(1, added);
		assertEquals(250, mine.kc("vorkath"));
		assertEquals(1, mine.copyOf("vorkath").drops.size());
		assertEquals(100, mine.kc("zulrah"));
	}

	@Test
	public void dropsAreAddedOnceAndKillCountsTakeTheLarger()
	{
		Ledger mine = ledger();
		mine.setKc("zulrah", 100);
		Drop shared = mine.logDrop("zulrah", "Magic fang", 50, 1, null, Drop.SRC_MANUAL);
		Ledger backup = ledger();
		backup.setKc("zulrah", 400);
		backup.replaceWith(mine); // starts from the same drop (same id)
		backup.setKc("zulrah", 400);
		backup.logDrop("zulrah", "Tanzanite fang", 300, 1, null, Drop.SRC_MANUAL);
		int added = mine.mergeFrom(backup);
		assertEquals("only the drop that was not there", 1, added);
		assertEquals(400, mine.kc("zulrah"));
		List<String> items = new ArrayList<>();
		for (Drop d : mine.copyOf("zulrah").drops)
		{
			items.add(d.item);
		}
		assertTrue(items.contains("Magic fang"));
		assertTrue(items.contains("Tanzanite fang"));
		assertEquals(2, items.size());
		// merging the same backup again changes nothing
		assertEquals(0, mine.mergeFrom(backup));
		assertEquals(shared.id, mine.copyOf("zulrah").drops.get(0).id);
	}

	@Test
	public void theBackupIsNotChangedByTheMerge()
	{
		Ledger mine = ledger();
		mine.logDrop("zulrah", "Magic fang", 50, 1, null, Drop.SRC_MANUAL);
		Ledger backup = ledger();
		backup.logDrop("zulrah", "Tanzanite fang", 60, 1, null, Drop.SRC_MANUAL);
		mine.mergeFrom(backup);
		mine.logDrop("zulrah", "Serpentine visage", 70, 1, null, Drop.SRC_MANUAL);
		assertEquals(1, backup.copyOf("zulrah").drops.size());
	}

	@Test
	public void theViewIsRememberedAndSaved()
	{
		Ledger l = ledger();
		List<String> seen = new ArrayList<>();
		l.addListener(id -> seen.add(String.valueOf(id)));
		long rev = l.revision();
		long bossRev = l.revision("zulrah");
		l.setView("zulrah", "table", "new");
		assertEquals("zulrah", l.prefs().sel);
		assertEquals("table", l.prefs().tab);
		assertEquals("new", l.prefs().scope);
		assertTrue("saved", l.revision() > rev);
		assertEquals("no figure depends on it", bossRev, l.revision("zulrah"));
		assertEquals(1, seen.size());
		l.setView("zulrah", "table", "new");
		assertEquals("unchanged: nothing to save", 1, seen.size());
	}
}
