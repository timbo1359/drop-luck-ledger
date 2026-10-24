package com.dropluckledger.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.dropluckledger.TestData;
import com.dropluckledger.capture.BossIndex.KcLabel;
import com.dropluckledger.engine.Data;
import com.dropluckledger.engine.Data.Boss;
import org.junit.Test;

public class BossIndexTest
{
	private final Data data = TestData.get();
	private final BossIndex index = new BossIndex(data);

	private KcLabel kc(String message)
	{
		KcMessages.Kc k = KcMessages.parseKillCount(message);
		return k == null ? null : index.kc(k.label);
	}

	@Test
	public void noNameIsClaimedByTwoBosses()
	{
		assertTrue("kill-count names claimed twice: " + index.ambiguousKc(), index.ambiguousKc().isEmpty());
		assertTrue("loot names claimed twice: " + index.ambiguousLoot(), index.ambiguousLoot().isEmpty());
	}

	@Test
	public void everyBossIsReachableByNameAndByItsKillCountLabels()
	{
		for (Boss b : data.bosses)
		{
			assertEquals(b.name, b.id, index.loot(b.name));
			for (String label : b.kc)
			{
				assertEquals(label, b.id, index.loot(label));
				KcLabel kl = index.kc(label);
				assertNotNull(label, kl);
				assertEquals(label, b.id, kl.bossId);
				assertEquals(label, kl.label);
			}
		}
	}

	@Test
	public void realKillCountMessagesFindTheirBoss()
	{
		assertEquals("zulrah", kc("Your Zulrah kill count is: <col=ff0000>4</col>.").bossId);
		assertEquals("barrows", kc("Your Barrows chest count is: <col=ff0000>277</col>.").bossId);
		assertEquals("the-corrupted-gauntlet", kc("Your Corrupted Gauntlet completion count is: <col=ff0000>4729</col>.").bossId);
		assertEquals("the-gauntlet", kc("Your Gauntlet completion count is: <col=ff0000>123</col>.").bossId);
		assertEquals("the-nightmare", kc("Your Nightmare kill count is: <col=ff0000>1,130</col>").bossId);
		assertEquals("tzkal-zuk", kc("Your TzKal-Zuk kill count is: <col=ff0000>2</col>.").bossId);
		assertEquals("tztok-jad", kc("Your TzTok-Jad kill count is: <col=ff0000>2</col>.").bossId);
		assertEquals("yama", kc("Your Yama success count is: <col=ff0000>227</col>").bossId);
		assertEquals("tempoross", kc("Your Tempoross kill count is: <col=ff0000>55</col>.").bossId);
		assertEquals("kreearra", kc("Your Kree'arra kill count is: <col=ff0000>4</col>.").bossId);
		assertEquals("grotesque-guardians", kc("Your Grotesque Guardians kill count is: <col=ff0000>179</col>.").bossId);
		assertEquals("corporeal-beast", kc("Your Corporeal Beast kill count is: <col=ff0000>4</col>.").bossId);
	}

	@Test
	public void messagesThatAreNotBossKillsAreIgnored()
	{
		assertNull(kc("Your <col=6800bf>Kalphite Queen (Echo)</col> kill count is:<col=e00a19>1</col>"));
		assertNull(kc("Your <col=a53fff>Corrupted Hunllef (Echo)</col> kill count is: <col=ff3045>31</col>"));
		assertNull(kc("Your Agility Arena Total Ticket count is: <col=ff0000>206</col>."));
		assertNull(kc("Your herbiboar harvest count is: <col=ff0000>4091</col>."));
		assertNull(kc("Your Shayzien Basic Agility Course lap count is: @mes_hl_red@2</col>."));
		assertNull(KcMessages.parseKillCount("You have killed something."));
	}

	@Test
	public void theBossesThePluginLeavesOutAreNotFound()
	{
		assertNull(kc("Your Royal Titans kill count is: <col=ff0000>40</col>."));
		assertNull(kc("Your subdued Wintertodt count is: <col=ff0000>4</col>."));
		assertNull(index.loot("Eldric the Ice King"));
		assertNull(index.loot("Reward cart (Wintertodt)"));
	}

	@Test
	public void theRaidsAndTheirModesAreFound()
	{
		KcLabel cox = kc("Your completed Chambers of Xeric count is: <col=ff0000>51</col>.");
		assertEquals("chambers-of-xeric", cox.bossId);
		assertEquals(0, cox.patch.get("cm"));
		assertEquals(1, kc("Your completed Chambers of Xeric Challenge Mode count is: <col=ff0000>13</col>.").patch.get("cm"));
		KcLabel hm = kc("Your completed Theatre of Blood: Hard Mode count is: <col=ff0000>73</col>.");
		assertEquals("theatre-of-blood", hm.bossId);
		assertEquals("hard", hm.patch.get("mode"));
		assertEquals("entry", kc("Your completed Theatre of Blood: Entry Mode count is: <col=ff0000>2</col>.").patch.get("mode"));
		assertEquals("normal", kc("Your completed Theatre of Blood count is: <col=ff0000>12</col>.").patch.get("mode"));
		assertEquals("tombs-of-amascut", kc("Your completed Tombs of Amascut: Expert Mode count is: <col=ff0000>1</col>.").bossId);
		assertEquals("chambers-of-xeric", index.loot("Chambers of Xeric"));
		assertEquals("theatre-of-blood", index.loot("Theatre of Blood"));
		assertEquals("tombs-of-amascut", index.loot("Tombs of Amascut"));
		// the new raid: its wording is not known yet, so either spelling of its name is taken
		assertEquals("the-fractured-archive", kc("Your completed The Fractured Archive count is: <col=ff0000>3</col>.").bossId);
		assertEquals("the-fractured-archive", kc("Your Fractured Archive completion count is: <col=ff0000>3</col>.").bossId);
		assertEquals("the-fractured-archive", index.loot("The Fractured Archive"));
		assertEquals("the-fractured-archive", index.loot("Fractured Archive"));
		// and a collection log page's count lines
		assertEquals(1, index.kcShown("Chambers of Xeric (CM) completions").patch.get("cm"));
		assertEquals("hard", index.kcShown("Theatre of Blood (HM) completions").patch.get("mode"));
		assertEquals("entry", index.kcShown("Theatre of Blood: Entry Mode completions").patch.get("mode"));
		assertEquals("the-fractured-archive", index.kcShown("The Fractured Archive completions").bossId);
	}

	@Test
	public void awakenedVersionsAreFound()
	{
		KcLabel a = index.kc("Awakened Duke Sucellus");
		assertEquals("duke-sucellus", a.bossId);
		assertEquals(1, a.patch.get("awakened"));
		KcLabel n = index.kc("Duke Sucellus");
		assertEquals(0, n.patch.get("awakened"));
		KcLabel l = index.kc("Awakened The Leviathan");
		assertEquals("the-leviathan", l.bossId);
		assertEquals(1, l.patch.get("awakened"));
		KcLabel w = index.kc("The Whisperer (Awakened)");
		assertEquals("the-whisperer", w.bossId);
		assertEquals(1, w.patch.get("awakened"));
		assertNull(index.kc("Awakened Zulrah"));
	}

	@Test
	public void countsOnTheGamesScreensFindTheirBoss()
	{
		// collection log page lines
		assertEquals("zulrah", index.kcShown("Zulrah kills").bossId);
		assertEquals("barrows", index.kcShown("Barrows Chests opened").bossId);
		assertEquals("moons-of-peril", index.kcShown("Lunar Chests opened").bossId);
		assertEquals("the-gauntlet", index.kcShown("Gauntlet completions").bossId);
		assertEquals("the-corrupted-gauntlet", index.kcShown("Corrupted Gauntlet completions").bossId);
		KcLabel aw = index.kcShown("Duke Sucellus (awakened) kills");
		assertEquals("duke-sucellus", aw.bossId);
		assertEquals("Awakened Duke Sucellus", aw.label);
		assertEquals("Duke Sucellus", index.kcShown("Duke Sucellus kill count").label);
		// Boss Kill Log rows
		assertEquals("vorkath", index.kcShown("Vorkath").bossId);
		assertEquals("barrows", index.kcShown("Barrows Chests").bossId);
		// not a boss
		assertNull(index.kcShown("Obtained"));
		assertNull(index.kcShown("Chompy bird kills"));
		assertNull(index.kcShown("kills"));
		assertNull(index.kcShown(null));
	}

	@Test
	public void lootEventNames()
	{
		assertEquals("grotesque-guardians", index.loot("Dusk"));
		assertEquals("grotesque-guardians", index.loot("Dawn"));
		assertEquals("the-gauntlet", index.loot("Crystalline Hunllef"));
		assertEquals("the-corrupted-gauntlet", index.loot("Corrupted Hunllef"));
		assertEquals("tempoross", index.loot("Reward pool (Tempoross)"));
		assertEquals("abyssal-sire", index.loot("Unsired"));
		assertEquals("abyssal-sire", index.loot("Abyssal Sire"));
		assertEquals("barrows", index.loot("Barrows"));
		assertEquals("moons-of-peril", index.loot("Lunar Chest"));
		assertEquals("fortis-colosseum", index.loot("Fortis Colosseum"));
		assertEquals("doom-of-mokhaiotl", index.loot("Doom of Mokhaiotl"));
		assertEquals("the-nightmare", index.loot("The Nightmare"));
		assertEquals("vetion", index.loot("Vet'ion Reborn"));
		assertEquals("the-hueycoatl", index.loot("Hueycoatl body"));
		assertEquals("fortis-colosseum", index.loot("Sol Heredit"));
		assertEquals("crazy-archaeologist", index.loot("Crazy archaeologist"));
		assertNull(index.loot("Supply crate (Wintertodt)"));
		assertNull(index.loot("Casket (Tempoross)"));
		assertNull(index.loot("Goblin"));
	}

	@Test
	public void itemNamesMatchIgnoringCaseAndPunctuation()
	{
		assertEquals("Tanzanite fang", index.item("zulrah", "Tanzanite fang"));
		assertEquals("Tanzanite fang", index.item("zulrah", "tanzanite FANG"));
		assertNull(index.item("zulrah", "Goblin mail"));
		assertNull(index.item("not-a-boss", "Tanzanite fang"));
	}

	@Test
	public void messageParsing()
	{
		KcMessages.Kc k = KcMessages.parseKillCount("Your Nightmare kill count is: <col=ff0000>1,130</col>");
		assertEquals("Nightmare", k.label);
		assertEquals(1130, k.count);
		k = KcMessages.parseKillCount("Your completed Theatre of Blood: Hard Mode count is: <col=ff0000>13</col>.");
		assertEquals("Theatre of Blood Hard Mode", k.label);
		assertEquals("Pet snakeling", KcMessages.collectionLogItem("New item added to your collection log: <col=ef1020>Pet snakeling</col>"));
		assertEquals("Tanzanite fang", KcMessages.collectionLogItem("New item added to your collection log: Tanzanite fang"));
		assertNull(KcMessages.collectionLogItem("You killed a goblin"));
		assertTrue(KcMessages.isPetMessage("You have a funny feeling like you're being followed."));
		assertTrue(KcMessages.isPetMessage("<col=ef1020>You feel something weird sneaking into your backpack.</col>"));
		assertTrue(KcMessages.isPetMessage("You have a funny feeling like you would have been followed..."));
		assertTrue(!KcMessages.isPetMessage("You feel a funny sensation"));
	}
}
