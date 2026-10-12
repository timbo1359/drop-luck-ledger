package com.dropluckledger.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.dropluckledger.TestData;
import com.dropluckledger.engine.Data;
import org.junit.Test;

public class QuantitiesTest
{
	private final Data data = TestData.get();

	@Test
	public void amountsInTheDropTableAreRead()
	{
		assertEquals(3, Quantities.parse("3"), 0);
		assertEquals(37.5, Quantities.parse("35-40"), 0);
		assertEquals(11, Quantities.parse("11;17"), 0);
		assertEquals(200, Quantities.parse("200;300n"), 0);
		assertEquals(1500, Quantities.parse("1,500;2,250"), 0);
		assertNull(Quantities.parse("-"));
		assertNull(Quantities.parse(""));
		assertNull(Quantities.parse(null));
	}

	@Test
	public void aStackOfBoltRacksIsAboutOneHundredBarrowsChests()
	{
		// a Barrows chest gives 35-40 bolt racks
		assertEquals(37.5, Quantities.perDrop(data.boss("barrows"), "Bolt rack"), 1e-9);
		assertEquals(100, Quantities.drops(data.boss("barrows"), "Bolt rack", 3750));
		assertEquals(1, Quantities.drops(data.boss("barrows"), "Bolt rack", 20)); // never below one drop once something was received
	}

	@Test
	public void severalAmountsAreAveragedByHowLikelyEachIs()
	{
		// Amoxliatl's frozen tears: 2-4 at 6 in 10, 5-10 at 3 in 10, 10-20 at 1 in 10
		double each = 0.6 * 3 + 0.3 * 7.5 + 0.1 * 15;
		assertEquals(each, Quantities.perDrop(data.boss("amoxliatl"), "Frozen tear"), 1e-9);
		assertEquals(100, Quantities.drops(data.boss("amoxliatl"), "Frozen tear", (int) Math.round(each * 100)));
	}

	@Test
	public void itemsThatDropOneAtATimeAreNotChanged()
	{
		assertEquals(1, Quantities.perDrop(data.boss("zulrah"), "Tanzanite fang"), 0);
		assertEquals(7, Quantities.drops(data.boss("zulrah"), "Tanzanite fang", 7));
		assertEquals(0, Quantities.drops(data.boss("zulrah"), "Tanzanite fang", 0));
		assertEquals(0, Quantities.drops(data.boss("zulrah"), "Tanzanite fang", -3));
		// an item the boss does not list has no amount at all
		assertEquals(1, Quantities.perDrop(data.boss("zulrah"), "No such item"), 0);
	}
}
