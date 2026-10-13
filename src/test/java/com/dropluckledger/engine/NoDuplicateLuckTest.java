package com.dropluckledger.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.dropluckledger.TestData;
import com.dropluckledger.engine.Data.Boss;
import java.util.HashMap;
import org.junit.Test;

/**
 * Items with duplicate protection (the halberd pieces, the moon sets, the keris jewels...): only the pieces held the fewest of can
 * drop.  Their luck and their chances ahead follow the set as a whole.
 */
public class NoDuplicateLuckTest
{
	private final Data data = TestData.get();

	private Luck.Model araxxor(int kc, Object... drops)
	{
		Boss b = data.boss("araxxor");
		Luck.BossRecord rec = new Luck.BossRecord();
		rec.kc = kc;
		rec.segs.add(new Luck.Seg(1, Engine.defaultParams(b)));
		for (int i = 0; i < drops.length; i += 2)
		{
			rec.drops.add(new Luck.DropRec((String) drops[i], (Integer) drops[i + 1]));
		}
		return Luck.makeModel(b, rec, new HashMap<>());
	}

	@Test
	public void aPieceThatCannotDropNowCanOnceTheRestHave()
	{
		Luck.Model m = araxxor(1000, "Noxious pommel", 200, "Noxious point", 500);
		Luck.ItemAnalysis blade = Luck.analyse(m, "Noxious blade", 100);
		Luck.ItemAnalysis pommel = Luck.analyse(m, "Noxious pommel", 100);
		// the blade is the only piece left: it has the whole set's chance
		assertEquals(1 / 200.0, blade.pNext, 1e-12);
		assertEquals(1 - Math.pow(1 - 1 / 200.0, 100), blade.fwd.get(100), 1e-9);
		// a second pommel needs the blade first, but it is not impossible
		assertEquals(0, pommel.pNext, 0);
		assertTrue(pommel.fwd.get(1000) > 0.3);
		assertTrue(pommel.kto.get(0.5) < Double.POSITIVE_INFINITY);
	}

	@Test
	public void aPieceReceivedEarlyIsJudgedOverAllTheKills()
	{
		// one pommel in 300 kills.  Counting only the kills before the copy (after it the pommel cannot drop until the set is done)
		// would make it look lucky; the luck is that of the whole 300 kills, where the pommel's chance grows as the other pieces come
		Luck.Model m = araxxor(300, "Noxious pommel", 20);
		Luck.ItemAnalysis pommel = Luck.analyse(m, "Noxious pommel", 50);
		// by hand: the pommel is still to come while 0, 1 or 2 of the other pieces have dropped (3, 2 or 1 pieces share the 1/200)
		double[] st = {1, 0, 0};
		double got = 0;
		for (int k = 0; k < 300; k++)
		{
			double[] nx = new double[3];
			for (int i = 0; i < 3; i++)
			{
				double each = 1 / 200.0 / (3 - i);
				got += st[i] * each;
				nx[i] += st[i] * (1 - each * (3 - i));
				if (i < 2)
				{
					nx[i + 1] += st[i] * each * (2 - i);
				}
			}
			st = nx;
		}
		assertEquals(got, pommel.exceed, 1e-9);
		// mid-p: P(more than one) + half P(exactly one)
		assertEquals(pommel.another + 0.5 * (pommel.exceed - pommel.another), 1 - pommel.pct, 1e-9);
	}
}
