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

	// ------------------------------------------------------------------ the keris partisan jewels (Tombs of Amascut)

	private static final String[] JEWELS = {"Eye of the Corruptor", "Jewel of the Sun", "Breach of the Scarab", "Jewel of Amascut"};

	/**
	 * The chance of each jewel per raid at a kill count, holding the copies given (jewel order as in {@link #JEWELS}).
	 */
	private double[] jewels(int kc, int... owned)
	{
		Boss b = data.boss("tombs-of-amascut");
		java.util.Map<String, Integer> have = new HashMap<>();
		for (int i = 0; i < owned.length; i++)
		{
			have.put(JEWELS[i], owned[i]);
		}
		Engine.Ctx ctx = new Engine.Ctx()
		{
			@Override
			public int owned(Data.Row row)
			{
				return have.getOrDefault(row.name, 0);
			}

			@Override
			public double since(Data.Row row)
			{
				return 0;
			}
		};
		java.util.Map<String, Engine.ItemProb> p = Engine.itemProbs(b, Engine.makeEnv(b, Engine.defaultParams(b), new HashMap<>(), kc), ctx, null);
		double[] out = new double[4];
		for (int i = 0; i < 4; i++)
		{
			Engine.ItemProb ip = p.get(JEWELS[i]);
			out[i] = ip == null ? 0 : ip.p;
		}
		return out;
	}

	@Test
	public void theJewelsRiseUntilAllFourAreOwnedThenGoBackToTheirBaseRate()
	{
		// 1/50 each at first, 3/50 each from 75 raids
		assertEquals(1 / 50.0, jewels(0)[0], 1e-12);
		assertEquals(3 / 50.0, jewels(75)[3], 1e-12);
		assertEquals(3 / 50.0, jewels(400)[1], 1e-12);
		// while any is missing a hit gives one not owned: 1/37.5, 1/25, 1/12.5 at the base rate
		double[] one = jewels(0, 1);
		assertEquals(0, one[0], 0);
		assertEquals(1 / 37.5, one[1], 1e-12);
		assertEquals(1 / 25.0, jewels(0, 1, 1)[3], 1e-12);
		assertEquals(1 / 12.5, jewels(0, 1, 1, 1)[3], 1e-12);
		assertEquals(12 / 50.0, jewels(75, 1, 1, 1)[3], 1e-12);
		// all four owned: back to 1/50 each whatever the kill count, and a duplicate can drop again
		for (double p : jewels(300, 1, 1, 1, 1))
		{
			assertEquals(1 / 50.0, p, 1e-12);
		}
		for (double p : jewels(300, 2, 1, 1, 1))
		{
			assertEquals(1 / 50.0, p, 1e-12);
		}
	}

	private Luck.Model toa(int kc, Object... drops)
	{
		Boss b = data.boss("tombs-of-amascut");
		Luck.BossRecord rec = new Luck.BossRecord();
		rec.kc = kc;
		java.util.Map<String, Object> params = Engine.defaultParams(b);
		params.put("raidLevel", 400);
		params.put("points", 30000);
		rec.segs.add(new Luck.Seg(1, params));
		for (int i = 0; i < drops.length; i += 2)
		{
			rec.drops.add(new Luck.DropRec((String) drops[i], (Integer) drops[i + 1]));
		}
		return Luck.makeModel(b, rec, new HashMap<>());
	}

	@Test
	public void theJewelsLuckFollowsTheSetAndThenTheBaseRate()
	{
		// the figures of the web page's luck model for the same raids
		Luck.Model m = toa(300, "Eye of the Corruptor", 10, "Jewel of the Sun", 20, "Breach of the Scarab", 30, "Jewel of Amascut", 40,
			"Eye of the Corruptor", 200);
		Luck.ItemAnalysis eye = Luck.analyse(m, "Eye of the Corruptor", 50);
		Luck.ItemAnalysis sun = Luck.analyse(m, "Jewel of the Sun", 50);
		assertEquals(6.328946321716795, eye.expected, 1e-9);
		assertEquals(0.01745852032777906, eye.pct, 1e-9);
		assertEquals(0.002366893501001721, sun.pct, 1e-9);
		assertEquals(1 / 50.0, sun.pNext, 1e-12);
		assertEquals(0.6358303199128831, sun.fwd.get(50), 1e-9);
		assertEquals(0.9947666512139663, sun.meter, 1e-9);
		// before, the rate stayed at 3/50 once all four were owned: three times too many expected
		assertTrue(eye.expected < 7);
		// a player still missing one jewel after 300 raids: it has the whole roll, 12/50
		Luck.ItemAnalysis last = Luck.analyse(toa(300, "Eye of the Corruptor", 10, "Jewel of the Sun", 20, "Breach of the Scarab", 30),
			"Jewel of Amascut", 50);
		assertEquals(6.328946321716858, last.expected, 1e-9);
		assertEquals(0.24, last.pNext, 1e-12);
		assertEquals(0.9999989018046156, last.fwd.get(50), 1e-9);
	}
}
