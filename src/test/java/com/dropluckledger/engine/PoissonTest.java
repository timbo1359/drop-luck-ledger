package com.dropluckledger.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.junit.Test;

/**
 * A count taken as Poisson, as the luck of a set of pieces is (all the Barrows equipment together).
 */
public class PoissonTest
{
	@Test
	public void theTailsMatchTheirFormulas()
	{
		assertEquals(1, Luck.poissonAtLeast(0, 3), 0);
		assertEquals(-Math.expm1(-0.5), Luck.poissonAtLeast(1, 0.5), 1e-15);
		assertEquals(1 - Math.exp(-2) * (1 + 2), Luck.poissonAtLeast(2, 2), 1e-14);
		// far in the tail it keeps its digits: P(X >= 30) with 5 expected is about 2.8e-14
		double tail = Luck.poissonAtLeast(30, 5);
		double sum = 0;
		double t = Math.exp(-5);
		for (int k = 1; k <= 30; k++)
		{
			t *= 5.0 / k;
		}
		for (int k = 30; k < 200; k++)
		{
			sum += t;
			t *= 5.0 / (k + 1);
		}
		assertEquals(sum, tail, sum * 1e-9);
		assertTrue(tail + "", tail > 2e-14 && tail < 4e-14);
		// nothing expected: none is certain
		assertEquals(0, Luck.poissonAtLeast(1, 0), 0);
	}

	@Test
	public void thePercentileIsTheMidPoint()
	{
		// none of 0.5 expected: tied with the players who have none, behind those with one or more
		assertEquals(0.5 * Math.exp(-0.5), Luck.poissonPct(0, 0.5), 1e-15);
		// one of 0.5 expected: ahead of those with none, tied with those with one
		assertEquals(Math.exp(-0.5) + 0.5 * 0.5 * Math.exp(-0.5), Luck.poissonPct(1, 0.5), 1e-15);
		// as many as expected, for a big count: about average
		assertEquals(0.5, Luck.poissonPct(400, 400), 0.02);
		assertEquals("vdry", Luck.luckLabel(Luck.poissonPct(5, 30)).key);
		assertEquals("vlucky", Luck.luckLabel(Luck.poissonPct(50, 30)).key);
	}

	@Test
	public void manySmallChancesAreAboutPoisson()
	{
		// 300 kills at 1 in 350: the count of copies is all but Poisson with the same average
		java.util.List<Luck.Run> runs = Collections.singletonList(new Luck.Run(1 / 350.0, 300));
		for (int m = 0; m <= 4; m++)
		{
			assertEquals(Luck.atLeast(m, runs), Luck.poissonAtLeast(m, 300 / 350.0), 0.002);
		}
	}
}
