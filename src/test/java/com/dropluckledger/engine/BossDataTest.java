package com.dropluckledger.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.dropluckledger.TestData;
import com.dropluckledger.engine.Data.Boss;
import com.dropluckledger.engine.Data.Row;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import org.junit.Test;

/**
 * The boss data says what the wiki says for the bosses that needed work: the per-kill chances at the default settings and the
 * settings for team play, the reward taken and the like.
 */
public class BossDataTest
{
	private final Data data = TestData.get();

	private Map<String, Engine.ItemProb> probs(String id, Object... settings)
	{
		Boss b = data.boss(id);
		Map<String, Object> v = Engine.defaultParams(b);
		for (int i = 0; i < settings.length; i += 2)
		{
			v.put((String) settings[i], settings[i + 1]);
		}
		return Engine.itemProbs(b, Engine.makeEnv(b, v, new HashMap<>(), 100), null, null);
	}

	private double p(String id, String item, Object... settings)
	{
		Engine.ItemProb ip = probs(id, settings).get(item);
		return ip == null ? 0 : ip.p;
	}

	private static void rate(double expectedDenominator, double p)
	{
		assertEquals("1/" + expectedDenominator + " vs 1/" + (1 / p), 1 / expectedDenominator, p, 1e-9 + 1e-4 / expectedDenominator);
	}

	@Test
	public void everyTrackedItemCanDropAtTheDefaultSettings()
	{
		StringBuilder bad = new StringBuilder();
		for (Boss b : data.bosses)
		{
			Map<String, Engine.ItemProb> pr = probs(b.id);
			for (String n : Catalog.defaultTracked(b))
			{
				Engine.ItemProb ip = pr.get(n);
				if ((ip == null || !(ip.p > 0)) && !inAnotherMode(b, n) && !b.meta().get(n).rateUnknown())
				{
					bad.append(b.id).append(": ").append(n).append('\n');
				}
			}
		}
		assertEquals("", bad.toString());
	}

	/**
	 * True when the item drops in one of the boss's other modes (challenge mode, hard mode ...), as its kill-count labels say.
	 */
	private boolean inAnotherMode(Boss b, String item)
	{
		for (String label : b.kc)
		{
			Map<String, Object> v = Engine.defaultParams(b);
			v.putAll(com.dropluckledger.capture.BossIndex.patchOf(b, label));
			Engine.ItemProb ip = Engine.itemProbs(b, Engine.makeEnv(b, v, new HashMap<>(), 100), null, null).get(item);
			if (ip != null && ip.p > 0)
			{
				return true;
			}
		}
		return false;
	}

	@Test
	public void onlyTheNewRaidHasRatesThatAreNotPublished()
	{
		java.util.Set<String> unknown = new java.util.TreeSet<>();
		for (Boss b : data.bosses)
		{
			for (Engine.ItemMeta m : b.meta().values())
			{
				if (m.rateUnknown())
				{
					unknown.add(b.id);
				}
			}
		}
		assertEquals("[the-fractured-archive]", unknown.toString());
		// its items never roll, so they add nothing to the maths, and the raid has no unique table to sum up
		Boss tfa = data.boss("the-fractured-archive");
		Map<String, Engine.ItemProb> pr = probs("the-fractured-archive");
		for (String n : Catalog.defaultTracked(tfa))
		{
			Engine.ItemProb ip = pr.get(n);
			assertTrue(n, ip == null || ip.p == 0);
		}
		assertTrue(Catalog.anyNames(tfa, Catalog.defaultTracked(tfa)).names.isEmpty());
		// the collection log's 17 slots, Ascension shards included
		assertEquals(17, Catalog.defaultTracked(tfa).size());
		assertTrue(Catalog.defaultTracked(tfa).contains("Ascension shards"));
	}

	@Test
	public void nexUniquesAreSharedByTheTeam()
	{
		rate(258, p("nex", "Torva platebody (damaged)"));
		rate(172, p("nex", "Zaryte vambraces"));
		rate(258 * 4, p("nex", "Torva platebody (damaged)", "team", 4));
		rate(258 / 0.3, p("nex", "Torva platebody (damaged)", "team", 4, "share", 30));
		rate(258 / 1.1, p("nex", "Torva platebody (damaged)", "mvp", 1));
		rate(500 * 5, p("nex", "Nexling", "team", 5));
	}

	@Test
	public void revenantMaledictusUniquesCanDrop()
	{
		// two normal rolls on the revenant dragon's table a kill
		assertEquals(1 - Math.pow(1 - 1 / 13333.5, 2), p("revenant-maledictus", "Amulet of avarice"), 1e-12);
		assertEquals(1 - Math.pow(1 - 1 / 1466.5, 2), p("revenant-maledictus", "Amulet of avarice", "skulled", 1, "task", 1), 1e-12);
		assertEquals(1 - Math.pow(1 - 1 / 8000.0, 2), p("revenant-maledictus", "Ancient relic"), 1e-12);
	}

	@Test
	public void theMaledictusGivesAnEmblemOrATotemEveryKill()
	{
		// the third roll is a guaranteed lower-tier artefact; which of the two is not published, so an even split is assumed
		assertEquals(1 - 0.5 * Math.pow(1 - 1 / 1333.33, 2), p("revenant-maledictus", "Ancient emblem"), 1e-9);
		assertEquals(1 - 0.5 * Math.pow(1 - 1 / 2000.0, 2), p("revenant-maledictus", "Ancient totem"), 1e-9);
		Boss b = data.boss("revenant-maledictus");
		// common drops now: not uniques, and Biggest Spoons leaves them out
		assertTrue(b.meta().get("Ancient emblem").noSpoon);
		assertTrue(b.meta().get("Ancient totem").noSpoon);
		java.util.List<String> any = Catalog.anyNames(b, Catalog.defaultTracked(b)).names;
		assertTrue(any.toString(), !any.contains("Ancient emblem") && !any.contains("Ancient totem") && any.contains("Amulet of avarice"));
	}

	@Test
	public void theBodyguardsYouKillCountWhoeverGotTheGeneralsLoot()
	{
		// each bodyguard's loot goes to whoever did the most damage to it
		double guards = 1 - Math.pow(1 - 1 / 1524.0, 3);
		assertEquals(guards, p("general-graardor", "Godsword shard 1", "looted", 0), 1e-12);
		assertEquals(0, p("general-graardor", "Godsword shard 1", "looted", 0, "minions", 0), 0);
		double all = 1 - (1 - 1 / 762.0) * (1 - guards);
		assertEquals(0.5 * all + 0.5 * guards, p("general-graardor", "Godsword shard 1", "looted", 50), 1e-12);
		assertEquals(0, p("general-graardor", "Bandos hilt", "looted", 0), 0);
		Boss b = data.boss("general-graardor");
		assertEquals("minions", b.extraCount);
		assertTrue(b.reads.contains("minions"));
		// the any-unique chance mixes the same way
		Map<String, Object> v = Engine.defaultParams(b);
		v.put("looted", 0);
		LinkedHashSet<String> shards = new LinkedHashSet<>(Arrays.asList("Godsword shard 1", "Godsword shard 2", "Godsword shard 3"));
		assertEquals(1 - Math.pow(1 - 3 / 1524.0, 3), Engine.anyProb(b, Engine.makeEnv(b, v, new HashMap<>(), 100), null, shards, null), 1e-12);
	}

	@Test
	public void theChambersStandardLootNeverRepeatsAnItem()
	{
		// two rolls without repeats out of 33 items: 2/33 of the raids without a unique
		double pu = 30000 / 867600.0;
		assertEquals((1 - pu) * 2 / 33, p("chambers-of-xeric", "Dark relic"), 1e-12);
		rate(11.4, p("chambers-of-xeric", "Clue scroll (elite)", "ca", 1));
	}

	private static Engine.Ctx owning(String... names)
	{
		final java.util.Set<String> own = new java.util.HashSet<>(Arrays.asList(names));
		return new Engine.Ctx()
		{
			@Override
			public int owned(Row row)
			{
				return own.contains(row.name) ? 1 : 0;
			}

			@Override
			public double since(Row row)
			{
				return 0;
			}
		};
	}

	@Test
	public void theFontLosesThePetsSlotOnceTheOrphanIsOwned()
	{
		Boss b = data.boss("abyssal-sire");
		Env env = Engine.makeEnv(b, Engine.defaultParams(b), new HashMap<>(), 100);
		double before = Engine.itemProbs(b, env, null, null).get("Abyssal whip").p;
		rate(100 * 128 / 12.0, before);
		env = Engine.makeEnv(b, Engine.defaultParams(b), new HashMap<>(), 100);
		double after = Engine.itemProbs(b, env, owning("Abyssal orphan"), null).get("Abyssal whip").p;
		assertEquals(before * 128 / 123, after, 1e-15);
	}

	@Test
	public void theFishBarrelAndTheTackleBoxComeOnce()
	{
		Boss b = data.boss("tempoross");
		assertTrue(p("tempoross", "Fish barrel") > 0);
		Map<String, Engine.ItemProb> pr = Engine.itemProbs(b, Engine.makeEnv(b, Engine.defaultParams(b), new HashMap<>(), 100), owning("Fish barrel"), null);
		assertTrue(pr.get("Fish barrel") == null || pr.get("Fish barrel").p == 0);
		assertTrue(pr.get("Tackle box").p > 0);
	}

	@Test
	public void theSmallerRulesTheWikiCheckFound()
	{
		// the cape traded in is always rolled at the off-task rate
		assertEquals(1 - 0.99 * 0.995, p("tztok-jad", "TzRek-Jad", "task", 1, "exchange", 1), 1e-12);
		assertEquals(1 - 0.995 * 0.995, p("tztok-jad", "TzRek-Jad", "exchange", 1), 1e-12);
		rate(100, p("tztok-jad", "TzRek-Jad", "task", 1));
		assertEquals(1 - (74 / 75.0) * 0.99, p("tzkal-zuk", "Jal-Nib-Rek", "task", 1, "exchange", 1), 1e-12);
		// the Tombs' pet and clue count only the points earned (25,000 with the 5,000 start)
		rate(700, p("tombs-of-amascut", "Tumeken's guardian"));
		rate(10, p("tombs-of-amascut", "Clue scroll (elite)"));
		rate(9.5, p("tombs-of-amascut", "Clue scroll (elite)", "ca", 1));
		// the Barrows clue is 1/200 a roll, at most one a chest
		assertEquals(1 - Math.pow(199 / 200.0, 7), p("barrows", "Clue scroll (elite)"), 1e-12);
		rate(200, p("barrows", "Clue scroll (elite)", "brothers", 0));
		// the Nightmare MVP's pet is 5% better in a team of five or more
		rate(4000 / 1.05, p("the-nightmare", "Little Nightmare", "team", 5, "mvp", 1));
		rate(3200, p("the-nightmare", "Little Nightmare", "team", 4, "mvp", 1));
		// Yama's dossier scales with contribution
		assertEquals((1 - 0.5 / 120) * 0.5 / 12, p("yama", "Dossier", "contrib", 50), 1e-12);
		// the elite Combat Achievement clue boost at the Theatre
		rate(23.75 / 3.5, p("theatre-of-blood", "Clue scroll (elite)", "mode", "hard", "ca", 1));
		// rows that were missing
		rate(100, p("alchemical-hydra", "Clue scroll (hard)"));
		rate(30, p("phantom-muspah", "Clue scroll (hard)"));
		rate(50, p("phantom-muspah", "Brimstone key", "konar", 1));
	}

	@Test
	public void eachMoonRollsItsOwnSet()
	{
		rate(224, p("moons-of-peril", "Blue Moon spear"));
		rate(224, p("moons-of-peril", "Eclipse atlatl"));
		assertEquals(0, p("moons-of-peril", "Eclipse atlatl", "eclipse", 0), 0);
		Boss b = data.boss("moons-of-peril");
		Map<String, Object> v = Engine.defaultParams(b);
		// the twelve set pieces; the atlatl darts, a common stack in the collection log, are hidden
		LinkedHashSet<String> all = new LinkedHashSet<>(Catalog.defaultTracked(b));
		assertEquals(12, all.size());
		assertFalse(all.contains("Atlatl dart"));
		assertTrue(b.meta().get("Atlatl dart").hidden);
		double any = Engine.anyProb(b, Engine.makeEnv(b, v, new HashMap<>(), 100), null, all, null);
		assertEquals(1 / 19.0, any, 0.0005);
	}

	@Test
	public void defaultsAreTheUsualWayToPlay()
	{
		rate(37.5, p("brutus", "Bottomless milk bucket (empty)"));
		assertEquals(0, p("brutus", "Bottomless milk bucket (empty)", "members", 0), 0);
		rate(3000, p("scurrius", "Scurry"));
		assertEquals(0, p("scurrius", "Scurry", "mvp", 0), 0);
	}

	@Test
	public void aTabletStopsDroppingOnceOwned()
	{
		// rolled after the unique table and the orb: about 1/25.8 a kill while the player has none
		assertEquals(1 / 25.8, p("duke-sucellus", "Frozen tablet"), 1e-4);
		Boss b = data.boss("duke-sucellus");
		Env env = Engine.makeEnv(b, Engine.defaultParams(b), new HashMap<>(), 100);
		Engine.Ctx owns = new Engine.Ctx()
		{
			@Override
			public int owned(Row row)
			{
				return row.name.equals("Frozen tablet") ? 1 : 0;
			}

			@Override
			public double since(Row row)
			{
				return 0;
			}
		};
		Engine.ItemProb tablet = Engine.itemProbs(b, env, owns, null).get("Frozen tablet");
		assertTrue(tablet == null || tablet.p == 0);
	}

	@Test
	public void doomRollsOncePerLevelOfTheRun()
	{
		rate(2500, p("doom-of-mokhaiotl", "Mokhaiotl cloth", "delve", 2));
		assertEquals(0, p("doom-of-mokhaiotl", "Eye of Ayak (uncharged)", "delve", 2), 0);
		// claiming at delve 4 rolled levels 2, 3 and 4 for the cloth
		double miss = (1 - 1 / 2500.0) * (1 - 1 / 2000.0) * (1 - 1 / 1350.0);
		assertEquals(1 - miss, p("doom-of-mokhaiotl", "Mokhaiotl cloth", "delve", 4), 1e-6);
		assertEquals(0, p("doom-of-mokhaiotl", "Dom", "delve", 5), 0);
		rate(1000, p("doom-of-mokhaiotl", "Dom", "delve", 6));
		double dom = 1 - (1 - 1 / 1000.0) * (1 - 1 / 750.0) * (1 - 1 / 500.0) * Math.pow(1 - 1 / 250.0, 3);
		assertEquals(dom, p("doom-of-mokhaiotl", "Dom", "delve", 11), 1e-9);
	}

	@Test
	public void itemsThatWereCountedTwiceOrTooRarelyAreRight()
	{
		rate(3500, p("maggot-king", "Maggot marquess"));
		rate(1502.4, p("maggot-king", "Maggot marquess", "choice", "eggs"));
		// every counted Obor kill opened his chest once
		rate(118, p("obor", "Hill giant club"));
	}

	@Test
	public void theGodWarsBodyguardsAddTheirDrops()
	{
		rate(762, p("general-graardor", "Godsword shard 1", "minions", 0));
		double withGuards = 1 - (1 - 1 / 762.0) * Math.pow(1 - 1 / 1524.0, 3);
		assertEquals(withGuards, p("general-graardor", "Godsword shard 1"), 1e-6);
		rate(381, p("general-graardor", "Bandos chestplate", "minions", 0));
	}

	@Test
	public void killsWhoseLootWentToAnotherPlayerPayNothing()
	{
		double full = p("general-graardor", "Bandos hilt");
		assertEquals(0, p("general-graardor", "Bandos hilt", "looted", 0), 0);
		assertEquals(full / 2, p("general-graardor", "Bandos hilt", "looted", 50), 1e-12);
		assertEquals(0, p("general-graardor", "Pet General Graardor", "looted", 0), 0);
		Boss b = data.boss("corporeal-beast");
		Map<String, Object> v = Engine.defaultParams(b);
		LinkedHashSet<String> sigils = new LinkedHashSet<>(Arrays.asList("Spectral sigil", "Arcane sigil", "Elysian sigil"));
		double any = Engine.anyProb(b, Engine.makeEnv(b, v, new HashMap<>(), 100), null, sigils, null);
		v.put("looted", 25);
		double quarter = Engine.anyProb(b, Engine.makeEnv(b, v, new HashMap<>(), 100), null, sigils, null);
		assertEquals(any / 4, quarter, 1e-12);
	}

	@Test
	public void wildernessBossesSplitByDamage()
	{
		rate(196, p("callisto", "Claws of Callisto"));
		rate(392, p("callisto", "Claws of Callisto", "share", 50));
		rate(3000, p("callisto", "Callisto cub", "share", 50));
	}

	@Test
	public void theNightmareSharesItsUniquesByDamage()
	{
		rate(300, p("the-nightmare", "Nightmare staff"));
		rate(1500, p("the-nightmare", "Nightmare staff", "team", 5));
		rate(300 / 0.4, p("the-nightmare", "Nightmare staff", "team", 5, "share", 40));
		rate(800 * 5, p("the-nightmare", "Little Nightmare", "team", 5));
	}
}
