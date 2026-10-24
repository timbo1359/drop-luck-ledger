package com.dropluckledger.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.dropluckledger.TestData;
import com.dropluckledger.engine.Data.Boss;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Copies with no kill number (brought in from the collection log or the Loot Tracker) of items whose chance changes with the copies
 * already owned.  When they came is not known, so the maths gives them the kills a usual history would have; before, they counted
 * as owned only after the last kill, so every earlier kill was worked out as if the player had none.
 */
public class UnknownKillLuckTest
{
	private final Data data = TestData.get();

	/**
	 * @param drops item, kill number (null when unknown), item, kill number...
	 */
	private Luck.Model model(String id, int kc, Map<String, Object> settings, Object... drops)
	{
		Boss b = data.boss(id);
		Luck.BossRecord rec = new Luck.BossRecord();
		rec.kc = kc;
		Map<String, Object> v = Engine.defaultParams(b);
		v.putAll(settings);
		rec.segs.add(new Luck.Seg(1, v));
		for (int i = 0; i < drops.length; i += 2)
		{
			rec.drops.add(new Luck.DropRec((String) drops[i], (Integer) drops[i + 1]));
		}
		return Luck.makeModel(b, rec, new HashMap<>());
	}

	private static Map<String, Object> toa()
	{
		Map<String, Object> v = new HashMap<>();
		v.put("raidLevel", 150);
		v.put("points", 10000);
		return v;
	}

	private static final String THREAD = "Thread of Elidinis";

	@Test
	public void threadsWithNoKillNumberAreNotCountedAsMissingEveryRaid()
	{
		// three threads in 115 raids, all brought in from the collection log: the first thread is 1/10 rising to 3/10 until one is
		// owned, then 1/50, so about 1 + 110/50 were to be expected, not 3/10 a raid for every raid
		Luck.Model m = model("tombs-of-amascut", 115, toa(), THREAD, null, THREAD, null, THREAD, null);
		Luck.ItemAnalysis a = Luck.analyse(m, THREAD, 50);
		assertTrue("expected " + a.expected, a.expected > 2 && a.expected < 3.5);
		assertNotEquals("vdry", a.label.key);
		assertEquals(1 / 50.0, a.pNext, 1e-12);
		// still three copies with no kill number, and no dry streak
		assertEquals(3, a.O);
		assertEquals(3, a.unknown);
		assertFalse(a.streakKnown);
		assertTrue(m.known(THREAD).isEmpty());
		// the first goes where the expected copies reach a half
		double c = 0;
		int first = 0;
		for (int k = 1; k <= 115 && first == 0; k++)
		{
			c += m.pAt(THREAD, k, 0);
			if (c >= 0.5)
			{
				first = k;
			}
		}
		assertEquals(0, m.ownedAt(THREAD, first));
		assertEquals(1, m.ownedAt(THREAD, first + 1));
		// close to the same threads with their kills known
		Luck.ItemAnalysis known = Luck.analyse(model("tombs-of-amascut", 115, toa(), THREAD, 5, THREAD, 55, THREAD, 105), THREAD, 50);
		assertEquals(known.expected, a.expected, 0.3);
	}

	@Test
	public void unknownCopiesComeBeforeKnownOnesAndExtraOnesAtTheLastKill()
	{
		Luck.Model m = model("tombs-of-amascut", 115, toa(), THREAD, null, THREAD, 110);
		assertEquals(1, m.ownedAt(THREAD, 109));
		assertEquals(2, m.ownedAt(THREAD, 111));
		// more than a usual history holds: the rest count from the next kill
		Luck.Model many = model("tombs-of-amascut", 20, toa(), THREAD, null, THREAD, null, THREAD, null, THREAD, null, THREAD, null, THREAD, null);
		assertTrue(many.ownedAt(THREAD, 20) < 6);
		assertEquals(6, many.ownedAt(THREAD, 21));
		// no kills yet: owned from the first kill
		Luck.Model none = model("tombs-of-amascut", 0, toa(), THREAD, null);
		assertEquals(1, none.ownedAt(THREAD, 1));
		assertEquals(1 / 50.0, none.pAt(THREAD, 1), 1e-12);
	}

	@Test
	public void theDesertTreasureQuartzWithNoKillNumber()
	{
		// the quartz ramps from 1/200 to 1/50 by 300 kills until the first one, then goes back to 1/200
		Luck.ItemAnalysis a = Luck.analyse(model("duke-sucellus", 400, new HashMap<>(), "Ice quartz", null, "Ice quartz", null), "Ice quartz", 50);
		assertTrue("expected " + a.expected, a.expected > 1.5 && a.expected < 3);
		assertNotEquals("vdry", a.label.key);
	}

	@Test
	public void vorkathsHeadIsGuaranteedOnTheFiftiethKillOnly()
	{
		Luck.Model m = model("vorkath", 500, new HashMap<>());
		assertEquals(1 / 50.0, m.pAt("Vorkath's head", 49), 1e-12);
		assertEquals(1, m.pAt("Vorkath's head", 50), 0);
		assertEquals(1 / 50.0, m.pAt("Vorkath's head", 51), 1e-12);
		// not every 50 kills since the last head
		assertEquals(1 / 50.0, m.pAt("Vorkath's head", 100), 1e-12);
		assertEquals(1 + 499 / 50.0, Luck.analyse(m, "Vorkath's head", 50).expected, 1e-9);
		// heads with no kill number change nothing, and the next kill is not "guaranteed"
		Object[] heads = new Object[24];
		for (int i = 0; i < heads.length; i += 2)
		{
			heads[i] = "Vorkath's head";
		}
		Luck.ItemAnalysis a = Luck.analyse(model("vorkath", 500, new HashMap<>(), heads), "Vorkath's head", 50);
		assertEquals(1 + 499 / 50.0, a.expected, 1e-9);
		assertEquals(1 / 50.0, a.pNext, 1e-12);
	}

	@Test
	public void theSlepeyTabletAlwaysComesOnTheTwentyFifthKill()
	{
		Luck.Model m = model("phosanis-nightmare", 200, new HashMap<>());
		assertEquals(1 / 25.0, m.pAt("Slepey tablet", 24), 1e-12);
		assertEquals(1, m.pAt("Slepey tablet", 25), 0);
		assertEquals(1 / 25.0, m.pAt("Slepey tablet", 50), 1e-12);
	}

	@Test
	public void theKerisJewelsAndThePendantOfAtesAreNoSpoons()
	{
		Boss toa = data.boss("tombs-of-amascut");
		for (String j : new String[]{"Eye of the Corruptor", "Jewel of the Sun", "Breach of the Scarab", "Jewel of Amascut"})
		{
			assertTrue(j, toa.meta().get(j).noSpoon);
		}
		assertTrue(data.boss("amoxliatl").meta().get("Pendant of Ates (inert)").noSpoon);
		assertFalse(toa.meta().get(THREAD).noSpoon);
	}
}
