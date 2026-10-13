package com.dropluckledger.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.dropluckledger.TestData;
import com.dropluckledger.capture.Capture.Loot;
import com.dropluckledger.capture.Capture.Stack;
import com.dropluckledger.engine.Data;
import com.dropluckledger.ledger.BossState;
import com.dropluckledger.ledger.Drop;
import com.dropluckledger.ledger.Ledger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class CaptureTest
{
	private final Data data = TestData.get();
	private final BossIndex index = new BossIndex(data);
	private Ledger ledger;
	private Capture capture;
	private final List<String> logged = new ArrayList<>();

	@Before
	public void setUp()
	{
		ledger = new Ledger(data, () -> 1_000L);
		capture = new Capture(ledger, index);
		capture.setListener((boss, item, kill) -> logged.add(boss + ":" + item + "@" + kill));
	}

	private void kc(String message, int tick)
	{
		KcMessages.Kc k = KcMessages.parseKillCount(message);
		assertNotNull(message, k);
		capture.onKillCount(k.label, k.count, tick);
	}

	private static Loot loot(String name, int tick, String... items)
	{
		return lootWith(name, tick, null, items);
	}

	private static Loot lootWith(String name, int tick, Map<String, Object> hints, String... items)
	{
		List<Stack> st = new ArrayList<>();
		for (String i : items)
		{
			st.add(new Stack(i, 1));
		}
		return new Loot(name, 1, st, hints, tick);
	}

	private BossState boss(String id)
	{
		return ledger.copyOf(id);
	}

	private static String zulrahKc(int n)
	{
		return "Your Zulrah kill count is: <col=ff0000>" + n + "</col>.";
	}

	@Test
	public void killCountThenLoot()
	{
		kc(zulrahKc(100), 10);
		assertEquals(100, boss("zulrah").kc);
		capture.onLoot(loot("Zulrah", 12, "Coins", "Tanzanite fang", "Zulrah's scales"));
		BossState z = boss("zulrah");
		assertEquals(100, z.kc);
		assertEquals(1, z.drops.size());
		Drop d = z.drops.get(0);
		assertEquals("Tanzanite fang", d.item);
		assertEquals(Integer.valueOf(100), d.kc);
		assertEquals(Drop.SRC_LIVE, d.src);
		assertEquals(Integer.valueOf(100), z.labelKc.get("Zulrah"));
		assertNull(z.prov);
		assertEquals(Collections.singletonList("zulrah:Tanzanite fang@100"), logged);

		kc(zulrahKc(101), 40);
		capture.onLoot(loot("Zulrah", 42, "Coins"));
		kc(zulrahKc(102), 70);
		capture.onLoot(loot("Zulrah", 72, "Magic fang", "Tanzanite fang"));
		z = boss("zulrah");
		assertEquals(102, z.kc);
		assertEquals(3, z.drops.size());
		assertEquals(Integer.valueOf(102), z.drops.get(1).kc);
		assertEquals(Integer.valueOf(102), z.drops.get(2).kc);
		assertEquals(2, z.copies("Tanzanite fang"));
	}

	@Test
	public void lootBeforeItsKillCountOnAFreshLedger()
	{
		capture.onLoot(loot("Zulrah", 10, "Tanzanite fang"));
		BossState z = boss("zulrah");
		assertEquals(1, z.kc);
		assertEquals(Integer.valueOf(1), z.prov);
		assertEquals(Integer.valueOf(1), z.drops.get(0).kc);
		// the game's count arrives and says this was kill 2001
		kc(zulrahKc(2001), 11);
		z = boss("zulrah");
		assertEquals(2001, z.kc);
		assertNull(z.prov);
		assertNull(z.provDrops);
		assertEquals(Integer.valueOf(2001), z.drops.get(0).kc);
		assertEquals(Integer.valueOf(2001), z.labelKc.get("Zulrah"));
		// the next kill carries on from there
		kc(zulrahKc(2002), 50);
		capture.onLoot(loot("Zulrah", 52, "Magic fang"));
		z = boss("zulrah");
		assertEquals(2002, z.kc);
		assertEquals(Integer.valueOf(2002), z.drops.get(1).kc);
		assertEquals(1, z.copies("Tanzanite fang"));
	}

	@Test
	public void lootBeforeItsKillCountWhenTheCountIsKnown()
	{
		kc(zulrahKc(500), 1);
		capture.onLoot(loot("Zulrah", 3, "Coins"));
		capture.onLoot(loot("Zulrah", 100, "Tanzanite fang")); // next kill, loot first
		BossState z = boss("zulrah");
		assertEquals(501, z.kc);
		assertEquals(Integer.valueOf(1), z.prov);
		assertEquals(Integer.valueOf(501), z.drops.get(0).kc);
		kc(zulrahKc(501), 101);
		z = boss("zulrah");
		assertEquals(501, z.kc);
		assertNull(z.prov);
		assertEquals(Integer.valueOf(501), z.drops.get(0).kc);
	}

	@Test
	public void missedKillsAreCountedAndTheDropIsOnTheLastOne()
	{
		kc(zulrahKc(500), 1);
		// client was closed for five kills, then loot arrives before the count
		capture.onLoot(loot("Zulrah", 1000, "Tanzanite fang"));
		assertEquals(501, boss("zulrah").kc);
		kc(zulrahKc(505), 1001);
		BossState z = boss("zulrah");
		assertEquals(505, z.kc);
		assertEquals(Integer.valueOf(505), z.drops.get(0).kc);
	}

	@Test
	public void repeatedAndLowerCountsDoNothing()
	{
		kc(zulrahKc(500), 1);
		kc(zulrahKc(500), 2);
		assertEquals(500, boss("zulrah").kc);
		kc(zulrahKc(499), 3);
		assertEquals(500, boss("zulrah").kc);
		assertEquals(Integer.valueOf(499), boss("zulrah").labelKc.get("Zulrah"));
		kc(zulrahKc(500), 4);
		assertEquals(501, boss("zulrah").kc); // the game's count went 499 -> 500 again: that is one more kill from its point of view
	}

	@Test
	public void staleKillCountIsNotUsedForLateLoot()
	{
		kc(zulrahKc(10), 0);
		capture.onLoot(loot("Zulrah", 5000, "Tanzanite fang"));
		BossState z = boss("zulrah");
		assertEquals(11, z.kc);
		assertEquals(Integer.valueOf(1), z.prov);
	}

	@Test
	public void theGauntletKeepsItsCountWaitingForTheChest()
	{
		kc("Your Corrupted Gauntlet completion count is: <col=ff0000>51</col>.", 0);
		// the reward chest is opened a while after the Hunllef dies
		capture.onLoot(loot("Corrupted Gauntlet", 2000, "Enhanced crystal weapon seed", "Coins"));
		BossState c = boss("the-corrupted-gauntlet");
		assertEquals(51, c.kc);
		assertNull(c.prov);
		assertEquals(Integer.valueOf(51), c.drops.get(0).kc);
	}

	@Test
	public void modesBecomeSettingsPeriods()
	{
		kc("Your Duke Sucellus kill count is: <col=ff0000>10</col>.", 0);
		capture.onLoot(loot("Duke Sucellus", 10, "Coins"));
		capture.onKillCount("Awakened Duke Sucellus", 3, 500);
		capture.onLoot(loot("Duke Sucellus", 520, "Chromium ingot"));
		BossState d = boss("duke-sucellus");
		assertEquals(13, d.kc);
		// the three awakened kills are 11-13
		assertEquals(0, ((Number) d.paramsAt(10).getOrDefault("awakened", 0)).intValue());
		assertEquals(1, ((Number) d.paramsAt(11).get("awakened")).intValue());
		assertEquals(1, ((Number) d.paramsAt(13).get("awakened")).intValue());
		assertEquals(Integer.valueOf(13), d.drops.get(0).kc);
		// back to a normal kill
		kc("Your Duke Sucellus kill count is: <col=ff0000>11</col>.", 900);
		d = boss("duke-sucellus");
		assertEquals(14, d.kc);
		assertEquals(0, ((Number) d.paramsAt(14).get("awakened")).intValue());
		assertEquals(1, ((Number) d.paramsAt(13).get("awakened")).intValue());
		assertEquals(3, d.segs.size());
	}

	@Test
	public void aSettingFromTheLootStandsForTheRunsBefore()
	{
		ledger.setKc("doom-of-mokhaiotl", 4);
		Map<String, Object> hints = new LinkedHashMap<>();
		hints.put("delve", 8);
		hints.put("fishing", 80); // not a setting of this boss: ignored
		capture.onLoot(lootWith("Doom of Mokhaiotl", 600, hints, "Mokhaiotl cloth"));
		BossState d = boss("doom-of-mokhaiotl");
		assertEquals(5, d.kc);
		assertEquals(Integer.valueOf(5), d.drops.get(0).kc);
		assertEquals(8, ((Number) d.paramsAt(5).get("delve")).intValue());
		assertFalse(d.paramsAt(5).containsKey("fishing"));
		assertEquals(Integer.valueOf(5), d.seenFrom("delve"));
		// the runs counted before it are assumed to be at the first delve read
		assertEquals(8, ((Number) d.paramsAt(1).get("delve")).intValue());
		assertEquals(1, d.segs.size());
	}

	@Test
	public void hintsAreClampedToTheSettingRange()
	{
		kc("Your Tempoross kill count is: <col=ff0000>12</col>.", 0);
		Map<String, Object> hints = new LinkedHashMap<>();
		hints.put("fishing", 120);
		capture.onLoot(lootWith("Reward pool (Tempoross)", 1500, hints, "Tiny tempor"));
		BossState t = boss("tempoross");
		assertEquals(12, t.kc);
		assertEquals(99, ((Number) t.paramsAt(12).getOrDefault("fishing", 99)).intValue()); // 120 is clamped to the 99 maximum
		assertEquals("Tiny tempor", t.drops.get(0).item);
		kc("Your Tempoross kill count is: <col=ff0000>13</col>.", 3000);
		hints.put("fishing", 20);
		capture.onLoot(lootWith("Reward pool (Tempoross)", 3010, hints, "Coins"));
		t = boss("tempoross");
		assertEquals(35, ((Number) t.paramsAt(13).get("fishing")).intValue()); // and 20 to the 35 minimum
		assertEquals(99, ((Number) t.paramsAt(12).getOrDefault("fishing", 99)).intValue());
	}

	@Test
	public void awakenedKillsGetTheirOwnPeriod()
	{
		kc("Your Duke Sucellus kill count is: <col=ff0000>40</col>.", 0);
		capture.onKillCount("Awakened Duke Sucellus", 1, 200);
		BossState d = boss("duke-sucellus");
		assertEquals(41, d.kc);
		assertEquals(Integer.valueOf(1), d.labelKc.get("Awakened Duke Sucellus"));
		assertEquals(0, ((Number) d.paramsAt(40).getOrDefault("awakened", 0)).intValue());
		assertEquals(1, ((Number) d.paramsAt(41).get("awakened")).intValue());
	}

	@Test
	public void petFromTheCollectionLogMessage()
	{
		kc(zulrahKc(77), 0);
		capture.onLoot(loot("Zulrah", 2, "Coins"));
		capture.onCollectionLog("Pet snakeling", 3);
		BossState z = boss("zulrah");
		assertEquals(1, z.drops.size());
		assertEquals("Pet Snakeling", z.drops.get(0).item);
		assertEquals(Integer.valueOf(77), z.drops.get(0).kc);
		// the "funny feeling" line for the same kill adds nothing
		capture.onPetMessage(3);
		assertEquals(1, boss("zulrah").drops.size());
	}

	@Test
	public void petFromTheFunnyFeelingMessageWhenTheBossHasOnePet()
	{
		kc(zulrahKc(78), 0);
		capture.onPetMessage(1);
		assertEquals("Pet Snakeling", boss("zulrah").drops.get(0).item);
		// it arriving before the kill count works as well
		capture.onPetMessage(200);
		kc(zulrahKc(79), 201);
		BossState z = boss("zulrah");
		assertEquals(2, z.drops.size());
		assertEquals(Integer.valueOf(79), z.drops.get(1).kc);
	}

	@Test
	public void petMessageFarFromAnyKillIsIgnored()
	{
		kc(zulrahKc(78), 0);
		capture.onPetMessage(500);
		assertTrue(boss("zulrah").drops.isEmpty());
	}

	@Test
	public void collectionLogMessageFirstThenLootDoesNotDuplicate()
	{
		kc(zulrahKc(10), 0);
		capture.onCollectionLog("Tanzanite fang", 1);
		capture.onLoot(loot("Zulrah", 1, "Tanzanite fang"));
		assertEquals(1, boss("zulrah").copies("Tanzanite fang"));
		// a second copy on the next kill is a real second copy
		kc(zulrahKc(11), 100);
		capture.onLoot(loot("Zulrah", 101, "Tanzanite fang"));
		assertEquals(2, boss("zulrah").copies("Tanzanite fang"));
	}

	@Test
	public void unknownAndUntrackedThingsAreIgnored()
	{
		capture.onLoot(loot("Goblin", 1, "Bones"));
		capture.onKillCount("Goblin", 5, 1);
		assertTrue(ledger.activeBosses().isEmpty());
		ledger.setTracked("zulrah", "Tanzanite fang", false);
		kc(zulrahKc(3), 0);
		capture.onLoot(loot("Zulrah", 1, "Tanzanite fang", "Magic fang"));
		BossState z = boss("zulrah");
		assertEquals(1, z.drops.size());
		assertEquals("Magic fang", z.drops.get(0).item);
		assertEquals(3, z.kc);
	}

	@Test
	public void killsWithoutAnyCountMessageStillCount()
	{
		capture.onLoot(loot("Doom of Mokhaiotl", 1, "Dom"));
		capture.onLoot(loot("Doom of Mokhaiotl", 100, "Coins"));
		capture.onLoot(loot("Doom of Mokhaiotl", 200, "Mokhaiotl cloth"));
		BossState d = boss("doom-of-mokhaiotl");
		assertEquals(3, d.kc);
		assertEquals(Integer.valueOf(1), d.drops.get(0).kc);
		assertEquals(Integer.valueOf(3), d.drops.get(1).kc);
	}

	@Test
	public void sharedItemsRecordTheOtherBossesKillCounts()
	{
		kc("Your Callisto kill count is: <col=ff0000>200</col>.", 0);
		kc("Your Venenatis kill count is: <col=ff0000>150</col>.", 10);
		capture.onLoot(loot("Venenatis", 12, "Dragon pickaxe"));
		BossState v = boss("venenatis");
		assertEquals(1, v.drops.size());
		assertNotNull(v.drops.get(0).snap);
		assertEquals(Integer.valueOf(200), v.drops.get(0).snap.get("callisto"));
	}

	@Test
	public void resetForgetsWaitingKills()
	{
		kc(zulrahKc(10), 0);
		capture.reset();
		capture.onLoot(loot("Zulrah", 2, "Tanzanite fang"));
		BossState z = boss("zulrah");
		assertEquals(11, z.kc);
		assertEquals(Integer.valueOf(1), z.prov);
	}

	@Test
	public void manualEditsResetTheProvisionalState()
	{
		capture.onLoot(loot("Zulrah", 1, "Tanzanite fang"));
		assertEquals(Integer.valueOf(1), boss("zulrah").prov);
		ledger.setKc("zulrah", 40);
		assertNull(boss("zulrah").prov);
		kc(zulrahKc(41), 100); // first count seen: the game says 41, the ledger says 40
		assertEquals(41, boss("zulrah").kc);
	}

	@Test
	public void sampleSessionAcrossSeveralBosses()
	{
		int tick = 0;
		for (int i = 1; i <= 30; i++)
		{
			tick += 80;
			kc(zulrahKc(i), tick);
			capture.onLoot(loot("Zulrah", tick + 2, i % 10 == 0 ? new String[]{"Coins", "Magic fang"} : new String[]{"Coins"}));
			tick += 80;
			kc("Your Vorkath kill count is: <col=ff0000>" + i * 2 + "</col>.", tick);
			capture.onLoot(loot("Vorkath", tick + 1, "Bones"));
		}
		assertEquals(30, boss("zulrah").kc);
		assertEquals(60, boss("vorkath").kc);
		assertEquals(3, boss("zulrah").copies("Magic fang"));
		assertEquals(Arrays.asList(10, 20, 30), Arrays.asList(
			boss("zulrah").drops.get(0).kc, boss("zulrah").drops.get(1).kc, boss("zulrah").drops.get(2).kc));
		assertEquals(Arrays.asList("vorkath", "zulrah"), sorted(ledger.activeBosses()));
	}

	private static List<String> sorted(List<String> l)
	{
		List<String> c = new ArrayList<>(l);
		Collections.sort(c);
		return c;
	}
}
