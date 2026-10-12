package com.dropluckledger.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.dropluckledger.TestData;
import com.dropluckledger.capture.KcMessages;
import com.dropluckledger.ledger.BossState;
import com.dropluckledger.ledger.Ledger;
import org.junit.Test;

public class DropMessagesTest
{
	private static String plain(String s)
	{
		return KcMessages.stripTags(s);
	}

	@Test
	public void firstCopyAfterManyKills()
	{
		Ledger l = new Ledger(TestData.get());
		l.setKc("vorkath", 1234);
		l.logDrop("vorkath", "Skeletal visage", 1234, 1, null, "live");
		BossState s = l.copyOf("vorkath");
		assertEquals("Logged Skeletal visage at Vorkath kill 1,234 (the first one, after 1,233 kills).", plain(DropMessages.build(TestData.get(), "vorkath", s, "Skeletal visage", 1234)));
	}

	@Test
	public void laterCopyShowsKillsSinceTheLastOne()
	{
		Ledger l = new Ledger(TestData.get());
		l.setKc("vorkath", 900);
		l.logDrop("vorkath", "Dragonbone necklace", 500, 1, null, "live");
		l.logDrop("vorkath", "Dragonbone necklace", 900, 1, null, "live");
		String m = plain(DropMessages.build(TestData.get(), "vorkath", l.copyOf("vorkath"), "Dragonbone necklace", 900));
		assertEquals("Logged Dragonbone necklace at Vorkath kill 900 (400 kills since the last one).", m);
	}

	@Test
	public void backToBackIsOneKill()
	{
		Ledger l = new Ledger(TestData.get());
		l.logDrop("vorkath", "Vorki", 9, 1, null, "live");
		l.logDrop("vorkath", "Vorki", 10, 1, null, "live");
		assertTrue(plain(DropMessages.build(TestData.get(), "vorkath", l.copyOf("vorkath"), "Vorki", 10)).contains("(1 kill since the last one)"));
	}

	@Test
	public void saysNothingAboutTheStreakWhenAnImportedCopyHasNoKillNumber()
	{
		Ledger l = new Ledger(TestData.get());
		l.addOwned("vorkath", "Skeletal visage", 1);
		l.logDrop("vorkath", "Skeletal visage", 77, 1, null, "live");
		assertEquals("Logged Skeletal visage at Vorkath kill 77.", plain(DropMessages.build(TestData.get(), "vorkath", l.copyOf("vorkath"), "Skeletal visage", 77)));
	}

	@Test
	public void firstKillHasNoStreak()
	{
		Ledger l = new Ledger(TestData.get());
		l.logDrop("vorkath", "Vorki", 1, 1, null, "live");
		assertEquals("Logged Vorki at Vorkath kill 1.", plain(DropMessages.build(TestData.get(), "vorkath", l.copyOf("vorkath"), "Vorki", 1)));
	}

	@Test
	public void unknownBossFallsBackToItsId()
	{
		Ledger l = new Ledger(TestData.get());
		String m = plain(DropMessages.build(TestData.get(), "nobody", new BossState(), "Thing", 5));
		assertTrue(m, m.contains("at nobody kill 5"));
	}
}
