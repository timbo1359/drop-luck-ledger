package com.dropluckledger.engine;

import static org.junit.Assert.assertEquals;
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
				if (ip == null || !(ip.p > 0))
				{
					bad.append(b.id).append(": ").append(n).append('\n');
				}
			}
		}
		assertEquals("", bad.toString());
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
		rate(13333.5, p("revenant-maledictus", "Amulet of avarice"));
		rate(1466.5, p("revenant-maledictus", "Amulet of avarice", "skulled", 1, "task", 1));
	}

	@Test
	public void eachMoonRollsItsOwnSet()
	{
		rate(224, p("moons-of-peril", "Blue Moon spear"));
		rate(224, p("moons-of-peril", "Eclipse atlatl"));
		assertEquals(0, p("moons-of-peril", "Eclipse atlatl", "eclipse", 0), 0);
		Boss b = data.boss("moons-of-peril");
		Map<String, Object> v = Engine.defaultParams(b);
		LinkedHashSet<String> all = new LinkedHashSet<>(Catalog.defaultTracked(b));
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
