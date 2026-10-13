package com.dropluckledger.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.dropluckledger.TestData;
import com.dropluckledger.engine.Data.Boss;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/**
 * A boss whose setting the game reveals for every kill (the Maggot King's reward, whose loot it was) can have a period per kill.
 * The figures must stay exact and quick.
 */
public class ManyPeriodsTest
{
	private final Data data = TestData.get();

	@Test
	public void aPeriodPerKillGivesTheSameFiguresQuickly()
	{
		Boss b = data.boss("maggot-king");
		int kills = 3000;
		Luck.BossRecord rec = new Luck.BossRecord();
		rec.kc = kills;
		for (int k = 1; k <= kills; k++)
		{
			Map<String, Object> p = new LinkedHashMap<>();
			p.put("choice", k % 3 == 0 ? "eggs" : "stomach");
			rec.segs.add(new Luck.Seg(k, p));
		}
		rec.drops.add(new Luck.DropRec("Elder venator fang", 1200));
		long t0 = System.nanoTime();
		Luck.Model m = Luck.makeModel(b, rec, new HashMap<>());
		Luck.ItemAnalysis fang = Luck.analyse(m, "Elder venator fang", 50);
		Luck.ItemAnalysis pet = Luck.analyse(m, "Maggot marquess", 50);
		long ms = (System.nanoTime() - t0) / 1_000_000;
		assertTrue("took " + ms + " ms", ms < 3000);
		// 2,000 open-stomach kills at 1/340 for the fang; the pet at 1/3,500 on those and 1/1,502.4 on the 1,000 egg-takes
		assertEquals(1 - Math.pow(1 - 1 / 340.0, 2000), fang.cum, 1e-9);
		assertEquals(1 - Math.pow(1 - 1 / 3500.0, 2000) * Math.pow(1 - 1 / 1502.4, 1000), pet.cum, 1e-9);
		assertEquals(2000 / 340.0, fang.expected, 1e-6);
	}
}
