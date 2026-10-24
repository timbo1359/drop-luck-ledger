package com.dropluckledger.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
	public void aStackOfThrownaxesIsAboutOneHundredKills()
	{
		// the Alchemical Hydra gives 500-1000 dragon thrownaxes at a time
		assertEquals(750, Quantities.perDrop(data.boss("alchemical-hydra"), "Dragon thrownaxe"), 1e-9);
		assertEquals(100, Quantities.drops(data.boss("alchemical-hydra"), "Dragon thrownaxe", 75000));
		assertEquals(1, Quantities.drops(data.boss("alchemical-hydra"), "Dragon thrownaxe", 20)); // never below one drop once something was received
	}

	@Test
	public void severalAmountsAreAveragedByHowLikelyEachIs()
	{
		// the Phantom Muspah's ancient essence: 540-599 at 60 in 100, 885-995 at 23 in 100, 1,970-2,060 at 10 in 100
		double each = (0.6 * 569.5 + 0.23 * 940 + 0.1 * 2015) / (0.6 + 0.23 + 0.1);
		assertEquals(each, Quantities.perDrop(data.boss("phantom-muspah"), "Ancient essence"), 1e-9);
		assertEquals(100, Quantities.drops(data.boss("phantom-muspah"), "Ancient essence", (int) Math.round(each * 100)));
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

	@Test
	public void anItemDropsOneAtATimeOnlyWhenEveryRowGivesOne()
	{
		assertTrue(Quantities.oneAtATime(data.boss("zulrah"), "Tanzanite fang"));
		assertTrue(Quantities.oneAtATime(data.boss("general-graardor"), "Godsword shard 1")); // on two tables, one each
		assertTrue(Quantities.oneAtATime(data.boss("zulrah"), "Pet Snakeling")); // a pet's row has no amount
		assertFalse(Quantities.oneAtATime(data.boss("alchemical-hydra"), "Dragon knife")); // 500-1000
		assertFalse(Quantities.oneAtATime(data.boss("doom-of-mokhaiotl"), "Mokhaiotl waystone")); // 1-2
		assertFalse(Quantities.oneAtATime(data.boss("araxxor"), "Araxyte venom sac")); // 1, 6 or 12
		assertFalse(Quantities.oneAtATime(data.boss("the-fractured-archive"), "Ascension shards")); // "1+": an unknown amount
		assertFalse(Quantities.oneAtATime(data.boss("zulrah"), "No such item"));
	}
}
