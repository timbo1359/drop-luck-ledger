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

	@Test
	public void theOverviewRanksItemsByHowLuckyTheyAre()
	{
		Ledger l = ledger();
		l.setKc("zulrah", 2000);
		l.setKc("vorkath", 100);
		l.logDrop("vorkath", "Dragonbone necklace", 40, 1, null, Drop.SRC_MANUAL);
		Analyzer an = new Analyzer(l, null);
		Views.OverviewView o = an.overview(() -> false);
		assertEquals(2, o.tracked);
		assertEquals(2, o.bosses.size());
		assertFalse(o.driest.isEmpty());
		assertTrue(o.driest.size() <= Analyzer.DRY);
		for (int i = 0; i < o.driest.size(); i++)
		{
			Views.Rank r = o.driest.get(i);
			assertTrue(r.pct < 0.4);
			assertTrue(r.expected >= 0.5);
			if (i > 0)
			{
				assertTrue(o.driest.get(i - 1).pct <= r.pct);
			}
		}
		for (int i = 0; i < o.hottest.size(); i++)
		{
			Views.Rank r = o.hottest.get(i);
			assertTrue(r.got > 0);
			assertTrue(r.pct >= 0.6);
			if (i > 0)
			{
				assertTrue(o.hottest.get(i - 1).pct >= r.pct);
			}
		}
		// the boss list shows the one touched last first
		assertEquals("vorkath", o.bosses.get(0).id);
	}

	@Test
	public void aDryItemWithACopyIsStillRankedDry()
	{
		Ledger l = ledger();
		l.setKc("zulrah", 6000);
		l.logDrop("zulrah", "Magic fang", 100, 1, null, Drop.SRC_MANUAL);
		Views.OverviewView o = new Analyzer(l, null).overview(() -> false);
		Views.Rank fang = null;
		for (Views.Rank r : o.driest)
		{
			if (r.item.equals("Magic fang"))
			{
				fang = r;
			}
		}
		assertNotNull("one copy in 6,000 kills is dry", fang);
		assertEquals(1, fang.got);
	}

	@Test
	public void aSharedItemIsOneLineWithThePooledLuck()
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
		l.setKc(ids.get(0), 4000);
		l.setKc(ids.get(1), 3000);
		Analyzer an = new Analyzer(l, null);
		Views.OverviewView o = an.overview(() -> false);
		int lines = 0;
		for (Views.Rank r : o.driest)
		{
			if (r.item.equals(shared))
			{
				lines++;
				assertTrue(r.pooled);
				assertEquals(an.boss(ids.get(0), () -> false).items.stream().filter(i -> i.name.equals(r.item)).findFirst().get().pool.pct, r.pct, 0);
			}
		}
		for (Views.Rank r : o.hottest)
		{
			if (r.item.equals(shared))
			{
				lines++;
			}
		}
		assertTrue("one line at most for a shared item, not one per boss", lines <= 1);
		// both bosses show the same pooled luck
		Luck.Pool first = item(an.boss(ids.get(0), () -> false), shared).pool;
		Luck.Pool second = item(an.boss(ids.get(1), () -> false), shared).pool;
		assertEquals(first.pct, second.pct, 0);
		assertEquals(first.O, second.O);
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
	public void dataConfidenceFollowsTheNotes()
	{
		Views.Confidence c = Views.confidence(data.boss("zulrah"));
		assertNotNull(c.level);
		assertTrue(c.level.equals("high") || c.level.equals("medium") || c.level.equals("medium-low"));
		int bad = 0;
		for (Data.Boss b : data.bosses)
		{
			Views.Confidence x = Views.confidence(b);
			if (!x.level.equals("high"))
			{
				bad++;
				assertTrue(!x.unknown.isEmpty() || !x.inferred.isEmpty() || !x.gaps.isEmpty());
			}
		}
		assertTrue(bad < data.bosses.size());
	}
}
