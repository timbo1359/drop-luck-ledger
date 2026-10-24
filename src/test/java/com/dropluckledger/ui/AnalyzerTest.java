package com.dropluckledger.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.dropluckledger.TestData;
import com.dropluckledger.engine.Catalog;
import com.dropluckledger.engine.Data;
import com.dropluckledger.engine.Luck;
import com.dropluckledger.ledger.Drop;
import com.dropluckledger.ledger.Ledger;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class AnalyzerTest
{
	private final Data data = TestData.get();

	private Ledger ledger()
	{
		return new Ledger(data, () -> 1_700_000_000_000L);
	}

	private Views.ItemView item(Views.BossView v, String name)
	{
		for (Views.ItemView i : v.items)
		{
			if (i.name.equals(name))
			{
				return i;
			}
		}
		throw new AssertionError("no card for " + name + " in " + v.items.size() + " cards");
	}

	@Test
	public void aBossPageHasTheFiguresTheEngineGives()
	{
		Ledger l = ledger();
		l.setKc("zulrah", 500);
		l.logDrop("zulrah", "Magic fang", 120, 1, null, Drop.SRC_MANUAL);
		Analyzer an = new Analyzer(l, null);
		Views.BossView v = an.boss("zulrah", () -> false);
		assertNotNull(v);
		assertEquals(500, v.kc);
		assertEquals(50, v.window);
		Luck.Model m = Luck.makeModel(data.boss("zulrah"), l.record("zulrah"), l.globals());
		Luck.ItemAnalysis want = Luck.analyse(m, "Magic fang", 50);
		Luck.ItemAnalysis got = item(v, "Magic fang").a;
		assertEquals(want.O, got.O);
		assertEquals(want.meter, got.meter, 0);
		assertEquals(want.pct, got.pct);
		assertEquals(want.cum, got.cum);
		assertEquals(want.streak, got.streak);
		assertEquals(380, got.streak);
		assertEquals("unique", item(v, "Magic fang").kind);
		assertEquals(l.trackedNames("zulrah").size(), v.items.size());
	}

	@Test
	public void petsAreSeparatedFromUniquesAndOtherDrops()
	{
		Ledger l = ledger();
		l.setKc("zulrah", 100);
		Analyzer an = new Analyzer(l, null);
		Views.BossView v = an.boss("zulrah", () -> false);
		boolean pet = false;
		boolean unique = false;
		for (Views.ItemView i : v.items)
		{
			pet |= i.kind.equals("pet");
			unique |= i.kind.equals("unique");
		}
		assertTrue(pet);
		assertTrue(unique);
	}

	@Test
	public void figuresAreKeptUntilTheBossChanges()
	{
		Ledger l = ledger();
		l.setKc("zulrah", 500);
		l.setKc("vorkath", 200);
		Analyzer an = new Analyzer(l, null);
		Luck.ItemAnalysis first = item(an.boss("zulrah", () -> false), "Magic fang").a;
		assertSame(first, item(an.boss("zulrah", () -> false), "Magic fang").a);
		l.addKc("vorkath", 5);
		assertSame("another boss changed", first, item(an.boss("zulrah", () -> false), "Magic fang").a);
		l.addKc("zulrah", 1);
		Luck.ItemAnalysis later = item(an.boss("zulrah", () -> false), "Magic fang").a;
		assertNotSame(first, later);
		assertEquals(501, later.K);
		// a change to the luck window changes every figure
		l.updatePrefs(p -> p.window = 100);
		Luck.ItemAnalysis wide = item(an.boss("zulrah", () -> false), "Magic fang").a;
		assertEquals(100, wide.window);
	}

	@Test
	public void theUniqueTableCardNeedsTwoUniques()
	{
		Ledger l = ledger();
		l.setKc("zulrah", 300);
		Analyzer an = new Analyzer(l, null);
		Views.BossView v = an.boss("zulrah", () -> false);
		assertNotNull(v.any);
		assertTrue(v.any.names.size() >= 2);
		Luck.AnyUnique direct = Luck.anyUnique(Luck.makeModel(data.boss("zulrah"), l.record("zulrah"), l.globals()), v.any.names, 50);
		assertEquals(direct.meter, v.any.u.meter, 0);
		// nothing before the first kill
		Ledger empty = ledger();
		assertNull(new Analyzer(empty, null).boss("zulrah", () -> false).any);
	}

	@Test
	public void anItemSeveralBossesDropIsPooled()
	{
		Map<String, List<String>> idx = Catalog.logIndex(data);
		String shared = null;
		for (Map.Entry<String, List<String>> e : idx.entrySet())
		{
			if (e.getValue().size() >= 2)
			{
				shared = e.getKey();
				break;
			}
		}
		assertNotNull("some item is listed by two bosses", shared);
		List<String> ids = idx.get(shared);
		Ledger l = ledger();
		l.setKc(ids.get(0), 400);
		l.setKc(ids.get(1), 250);
		Analyzer an = new Analyzer(l, null);
		assertTrue(an.shared(shared));
		Views.ItemView iv = item(an.boss(ids.get(0), () -> false), shared);
		assertNotNull(iv.pool);
		assertEquals(2, iv.pool.per.size());
		assertEquals(650, iv.pool.kills);
		assertTrue(iv.shared);
		// only one of the bosses has kills: no pool
		Ledger one = ledger();
		one.setKc(ids.get(0), 400);
		assertNull(item(new Analyzer(one, null).boss(ids.get(0), () -> false), shared).pool);
	}

	private static Views.Rank spoon(Views.OverviewView o, String item)
	{
		for (Views.Rank r : o.spoons)
		{
			if (r.item.equals(item))
			{
				return r;
			}
		}
		return null;
	}

	@Test
	public void theMokhaiotlWaystoneIsNoSpoon()
	{
		// a common drop of the standard table: far ahead of its rate is still no spoon
		Ledger l = ledger();
		l.setKc("doom-of-mokhaiotl", 20);
		for (int k = 1; k <= 20; k++)
		{
			l.logDrop("doom-of-mokhaiotl", "Mokhaiotl waystone", k, 2, null, Drop.SRC_LIVE);
		}
		l.logDrop("doom-of-mokhaiotl", "Mokhaiotl cloth", 5, 1, null, Drop.SRC_LIVE);
		Analyzer an = new Analyzer(l, null);
		Views.OverviewView o = an.overview(() -> false);
		assertNull(spoon(o, "Mokhaiotl waystone"));
		assertNotNull(spoon(o, "Mokhaiotl cloth"));
		// it is still tracked and judged on the boss page
		Views.ItemView w = item(an.boss("doom-of-mokhaiotl", () -> false), "Mokhaiotl waystone");
		assertNotNull(w);
		assertEquals(20, w.a.O);
	}

	@Test
	public void theBiggestSpoonsComeLuckiestFirst()
	{
		Ledger l = ledger();
		l.setKc("zulrah", 2000);
		l.setKc("vorkath", 100);
		l.logDrop("vorkath", "Dragonbone necklace", 40, 1, null, Drop.SRC_MANUAL);
		l.logDrop("vorkath", "Vorki", 60, 1, null, Drop.SRC_MANUAL);
		Analyzer an = new Analyzer(l, null);
		Views.OverviewView o = an.overview(() -> false);
		assertEquals(2, o.tracked);
		assertEquals(2, o.active);
		assertEquals(2, o.bosses.size());
		assertFalse(o.spoons.isEmpty());
		for (int i = 0; i < o.spoons.size(); i++)
		{
			Views.Rank r = o.spoons.get(i);
			assertTrue(r.got > 0);
			assertTrue(r.pct >= 0.6);
			if (i > 0)
			{
				assertTrue(o.spoons.get(i - 1).pct >= r.pct);
			}
		}
		// the pet at 1/3,000 within 100 kills is a bigger spoon than the necklace at 1/1,000
		assertEquals("Vorki", o.spoons.get(0).item);
		assertNotNull(spoon(o, "Dragonbone necklace"));
		// the boss list shows the one touched last first
		assertEquals("vorkath", o.bosses.get(0).id);
	}

	@Test
	public void aDryItemIsNoSpoon()
	{
		Ledger l = ledger();
		l.setKc("zulrah", 6000);
		l.logDrop("zulrah", "Magic fang", 100, 1, null, Drop.SRC_MANUAL);
		Views.OverviewView o = new Analyzer(l, null).overview(() -> false);
		assertNull("one copy in 6,000 kills is dry", spoon(o, "Magic fang"));
	}

	@Test
	public void aSharedItemIsOneLineWithThePooledLuck()
	{
		String shared = "Virtus mask";
		List<String> ids = java.util.Arrays.asList("duke-sucellus", "vardorvis");
		assertTrue(Catalog.logIndex(data).get(shared).containsAll(ids));
		Ledger l = ledger();
		l.setKc(ids.get(0), 40);
		l.setKc(ids.get(1), 30);
		l.logDrop(ids.get(0), shared, 20, 1, null, Drop.SRC_MANUAL);
		l.logDrop(ids.get(1), shared, 10, 1, null, Drop.SRC_MANUAL);
		Analyzer an = new Analyzer(l, null);
		Views.OverviewView o = an.overview(() -> false);
		int lines = 0;
		for (Views.Rank r : o.spoons)
		{
			if (r.item.equals(shared))
			{
				lines++;
				assertTrue(r.pooled);
				assertEquals(item(an.boss(ids.get(0), () -> false), shared).pool.pct, r.pct, 0);
				assertEquals(ids.get(0), r.bossId); // the boss with the most kills
			}
		}
		assertEquals("one line for a shared item, not one per boss", 1, lines);
		// both bosses show the same pooled luck
		Luck.Pool first = item(an.boss(ids.get(0), () -> false), shared).pool;
		Luck.Pool second = item(an.boss(ids.get(1), () -> false), shared).pool;
		assertEquals(first.pct, second.pct, 0);
		assertEquals(first.O, second.O);
	}

	@Test
	public void theOverviewListsOnlyTheBossesToShow()
	{
		Ledger l = ledger();
		l.setKc("vorkath", 100);
		l.logDrop("vorkath", "Vorki", 60, 1, null, Drop.SRC_MANUAL);
		l.setKc("duke-sucellus", 40);
		l.setKc("vardorvis", 30);
		l.logDrop("duke-sucellus", "Virtus mask", 20, 1, null, Drop.SRC_MANUAL);
		l.logDrop("vardorvis", "Virtus mask", 10, 1, null, Drop.SRC_MANUAL);
		Analyzer an = new Analyzer(l, null);
		Views.Rank before = spoon(an.overview(() -> false), "Virtus mask");
		// only the Desert Treasure II bosses
		l.setShown(java.util.Collections.singletonList("vorkath"), false);
		Views.OverviewView o = an.overview(() -> false);
		assertEquals(2, o.tracked);
		assertEquals(3, o.active);
		assertNull(spoon(o, "Vorki"));
		assertTrue(o.bosses.stream().noneMatch(b -> b.id.equals("vorkath")));
		assertNotNull(spoon(o, "Virtus mask"));
		// an item several shown bosses drop keeps the luck of all the kills, and opens a boss that is shown
		l.setShown(java.util.Collections.singletonList("duke-sucellus"), false);
		o = an.overview(() -> false);
		Views.Rank virtus = spoon(o, "Virtus mask");
		assertEquals("vardorvis", virtus.bossId);
		assertEquals(before.pct, virtus.pct, 0);
		assertEquals(2, virtus.got);
		// nothing shown
		l.setShown(java.util.Collections.singletonList("vardorvis"), false);
		o = an.overview(() -> false);
		assertEquals(0, o.tracked);
		assertEquals(3, o.active);
		assertTrue(o.spoons.isEmpty());
		assertTrue(o.bosses.isEmpty());
		assertTrue(o.pooled.isEmpty());
		// every boss is still recorded
		assertEquals(100, l.kc("vorkath"));
	}

	@Test
	public void aDropLowersTheChanceOfTheNextCopyWithoutResettingIt()
	{
		Ledger l = ledger();
		l.setKc("zulrah", 1000);
		Analyzer an = new Analyzer(l, null);
		// none yet: the chance that a player would have had one by now (1,000 kills at 1/512)
		Views.BossView before = an.boss("zulrah", () -> false);
		Luck.ItemAnalysis dry = item(before, "Magic fang").a;
		assertEquals(dry.cum, dry.another, 0);
		assertTrue(String.valueOf(dry.another), dry.another > 0.84 && dry.another < 0.88);
		assertEquals(before.any.u.cum, before.any.u.another, 0);
		// a copy arrives on the latest kill: the chance of the next one drops, but the kills over the drop rate still count towards it
		l.logDrop("zulrah", "Magic fang", 1000, 1, null, Drop.SRC_MANUAL);
		Luck.ItemAnalysis hit = item(an.boss("zulrah", () -> false), "Magic fang").a;
		assertEquals(0, hit.streak);
		assertEquals(0, hit.meter, 0);
		assertTrue(String.valueOf(hit.another), hit.another > 0.4 && hit.another < dry.another);
		// it is the chance of two or more in 1,000 kills, which the percentile is built from
		assertEquals(hit.pct, 1 - 0.5 * (hit.exceed + hit.another), 1e-12);
		// and more kills raise it again
		l.addKc("zulrah", 1000);
		assertTrue(item(an.boss("zulrah", () -> false), "Magic fang").a.another > hit.another);
	}

	@Test
	public void aCopyWithNoKillNumberDoesNotMakeAStreak()
	{
		Ledger l = ledger();
		l.setKc("zulrah", 500);
		Analyzer an = new Analyzer(l, null);
		// nothing owned: the whole count is a streak
		Luck.ItemAnalysis none = item(an.boss("zulrah", () -> false), "Magic fang").a;
		assertTrue(none.streakKnown);
		assertEquals(500, none.streak);
		// a copy from before the plugin: it counts as received, but where the streak starts is not known
		l.addOwned("zulrah", "Magic fang", 1);
		Views.BossView v = an.boss("zulrah", () -> false);
		Luck.ItemAnalysis old = item(v, "Magic fang").a;
		assertEquals(1, old.O);
		assertFalse(old.streakKnown);
		assertFalse(v.any.u.streakKnown);
		// once a copy has a kill number the streak is known again
		l.logDrop("zulrah", "Magic fang", 120, 1, null, Drop.SRC_MANUAL);
		Luck.ItemAnalysis later = item(an.boss("zulrah", () -> false), "Magic fang").a;
		assertTrue(later.streakKnown);
		assertEquals(380, later.streak);
	}

	@Test
	public void aPooledItemKnowsItsStreakOnlyWhenSomeCopyHasAKillNumber()
	{
		Map<String, List<String>> idx = Catalog.logIndex(data);
		String shared = null;
		for (Map.Entry<String, List<String>> e : idx.entrySet())
		{
			if (e.getValue().size() >= 2)
			{
				shared = e.getKey();
				break;
			}
		}
		List<String> ids = idx.get(shared);
		Ledger l = ledger();
		l.setKc(ids.get(0), 400);
		l.setKc(ids.get(1), 250);
		Analyzer an = new Analyzer(l, null);
		assertTrue(item(an.boss(ids.get(0), () -> false), shared).pool.streakKnown);
		l.addOwned(ids.get(1), shared, 1);
		Views.ItemView iv = item(an.boss(ids.get(0), () -> false), shared);
		assertEquals(1, iv.pool.O);
		assertFalse(iv.pool.streakKnown);
		assertTrue(iv.pool.another < iv.pool.cum);
	}

	@Test
	public void theDropTableListsEveryItemMostLikelyFirst()
	{
		Ledger l = ledger();
		l.setKc("zulrah", 10);
		Views.TableView t = new Analyzer(l, null).table("zulrah");
		assertNotNull(t);
		assertFalse(t.rows.isEmpty());
		for (int i = 1; i < t.rows.size(); i++)
		{
			assertTrue(t.rows.get(i - 1).p >= t.rows.get(i).p);
		}
		Views.TableRow fang = null;
		for (Views.TableRow r : t.rows)
		{
			if (r.name.equals("Magic fang"))
			{
				fang = r;
			}
		}
		assertNotNull(fang);
		assertTrue(fang.log);
		assertTrue(fang.notable);
		assertTrue(fang.p > 0 && fang.p < 0.01);
	}

	@Test
	public void theChartRisesFromNothingTowardsOne()
	{
		Ledger l = ledger();
		l.setKc("zulrah", 800);
		Views.ChartData c = new Analyzer(l, null).chart("zulrah", "Magic fang");
		assertNotNull(c);
		assertEquals(61, c.x.length);
		assertEquals(0, c.y[0], 0);
		assertEquals(800, c.you);
		for (int i = 1; i < c.y.length; i++)
		{
			assertTrue(c.y[i] >= c.y[i - 1] - 1e-12);
		}
		assertTrue(c.y[60] > 0.9);
		assertTrue(c.xmax >= 30);
	}

	@Test
	public void aCancelledBuildGivesNothing()
	{
		Ledger l = ledger();
		l.setKc("zulrah", 100);
		Analyzer an = new Analyzer(l, null);
		assertNull(an.boss("zulrah", () -> true));
		assertNull(an.overview(() -> true));
	}

	@Test
	public void theBarrowsPiecesAreOneLineOfSpoons()
	{
		Ledger l = ledger();
		l.setKc("barrows", 300);
		List<String> pieces = new java.util.ArrayList<>();
		for (String n : l.trackedNames("barrows"))
		{
			if ("Barrows equipment".equals(data.boss("barrows").meta().get(n).group))
			{
				pieces.add(n);
			}
		}
		assertEquals(24, pieces.size());
		// one each of 21 pieces: each is ahead of its own rate, but together they are about what 300 chests give (about 20.6)
		for (int i = 0; i < 21; i++)
		{
			l.logDrop("barrows", pieces.get(i), 10 + i * 10, 1, null, Drop.SRC_LIVE);
		}
		Views.OverviewView o = new Analyzer(l, null).overview(() -> false);
		for (Views.Rank r : o.spoons)
		{
			assertFalse(r.item, pieces.contains(r.item));
		}
		assertNull(spoon(o, "Barrows equipment"));
		// ten more and the set as a whole is ahead: one line for all of it
		for (int i = 0; i < 10; i++)
		{
			l.logDrop("barrows", pieces.get(i), 15 + i * 10, 1, null, Drop.SRC_LIVE);
		}
		o = new Analyzer(l, null).overview(() -> false);
		Views.Rank set = spoon(o, "Barrows equipment");
		assertNotNull(set);
		assertEquals(31, set.got);
		assertEquals(24, set.pieces);
		assertEquals(300 * 7 / 102.0, set.expected, 0.6);
		assertEquals(Luck.poissonPct(31, set.expected), set.pct, 1e-12);
		assertEquals(pieces.get(0), set.icon);
		for (Views.Rank r : o.spoons)
		{
			assertFalse(r.item, pieces.contains(r.item));
		}
		// the Barrows page still has every piece
		assertEquals(l.trackedNames("barrows").size(), an(l).boss("barrows", () -> false).items.size());
	}

	@Test
	public void eachMoonIsOneLineOfSpoons()
	{
		Ledger l = ledger();
		l.setKc("moons-of-peril", 100);
		l.logDrop("moons-of-peril", "Blue Moon spear", 5, 1, null, Drop.SRC_LIVE);
		l.logDrop("moons-of-peril", "Blue Moon helm", 20, 1, null, Drop.SRC_LIVE);
		l.logDrop("moons-of-peril", "Blue Moon chestplate", 40, 1, null, Drop.SRC_LIVE);
		l.logDrop("moons-of-peril", "Blue Moon tassets", 60, 1, null, Drop.SRC_LIVE);
		Views.OverviewView o = new Analyzer(l, null).overview(() -> false);
		Views.Rank blue = spoon(o, "Blue Moon set");
		assertNotNull(blue);
		assertEquals(4, blue.got);
		assertEquals(4, blue.pieces);
		assertNull(spoon(o, "Blue Moon spear"));
		assertNull(spoon(o, "Eclipse Moon set"));
	}

	private Analyzer an(Ledger l)
	{
		return new Analyzer(l, null);
	}

	@Test
	public void onlyItemsWithoutARateAreListedAsUnknown()
	{
		// every boss in the data has its rates; an item marked unknown is listed (and left out of the luck figures)
		for (Data.Boss b : data.bosses)
		{
			for (String n : Views.unknownRates(b))
			{
				assertTrue(b.id + " " + n, b.allRows().stream().anyMatch(r -> r.name.equals(n) && "unknown".equals(r.rate)));
			}
		}
		assertTrue(Views.unknownRates(data.boss("zulrah")).isEmpty());
	}
}
