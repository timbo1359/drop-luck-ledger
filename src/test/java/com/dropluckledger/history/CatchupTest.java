package com.dropluckledger.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.dropluckledger.TestData;
import com.dropluckledger.capture.BossIndex;
import com.dropluckledger.capture.Capture;
import com.dropluckledger.engine.Data;
import com.dropluckledger.history.HistoryPlanner.ItemRow;
import com.dropluckledger.history.HistoryPlanner.KcRow;
import com.dropluckledger.history.HistoryPlanner.Plan;
import com.dropluckledger.ledger.BossState;
import com.dropluckledger.ledger.Drop;
import com.dropluckledger.ledger.Ledger;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/**
 * The ledger checked against what the game shows on screen (the Boss Kill Log for kill counts, collection log pages for items): what
 * would change is listed, and nothing changes until the list is added.
 */
public class CatchupTest
{
	private final Data data = TestData.get();
	private final BossIndex index = new BossIndex(data);
	private Ledger ledger;
	private Capture capture;
	private Catchup catchup;

	@Before
	public void setUp()
	{
		ledger = new Ledger(data, () -> 5_000L);
		capture = new Capture(ledger, index);
		catchup = new Catchup(ledger, capture, new HistoryPlanner(ledger, index), index);
	}

	private static Map<String, Integer> map(Object... kv)
	{
		Map<String, Integer> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2)
		{
			m.put((String) kv[i], (Integer) kv[i + 1]);
		}
		return m;
	}

	/**
	 * Every item on a page, owned or not, as the game names them.
	 */
	private static List<String> names(String... names)
	{
		return Arrays.asList(names);
	}

	private static final List<String> ZULRAH_PAGE = names("Pet snakeling", "Tanzanite fang", "Magic fang", "Serpentine visage", "Uncut onyx",
		"Tanzanite mutagen", "Magma mutagen", "Jar of swamp");
	private static final List<String> SKOTIZO_PAGE = names("Skotos", "Jar of darkness", "Dark claw", "Dark totem", "Uncut onyx", "Ancient shard");

	/**
	 * This computer saw Zulrah up to kill 100, with a Tanzanite fang at kill 50.
	 */
	private void playedHere()
	{
		capture.onKillCount("Zulrah", 50, 0);
		capture.onLoot(new Capture.Loot("Zulrah", 1, Collections.singletonList(new Capture.Stack("Tanzanite fang", 1)), null, 2));
		capture.onKillCount("Zulrah", 100, 200);
		capture.onTick(1000, true);
	}

	private Plan pageOnly(String title, Map<String, Integer> items, List<String> listed)
	{
		catchup.clear();
		catchup.addPage(title, map(), items, listed, catchup.stamp());
		return catchup.plan();
	}

	private Plan killLogOnly(Object... kv)
	{
		catchup.clear();
		catchup.addKillLog(map(kv), catchup.stamp());
		return catchup.plan();
	}

	private static ItemRow row(Plan plan, String bossId, String item)
	{
		for (ItemRow r : plan.items)
		{
			if (r.bossId.equals(bossId) && r.item.equals(item))
			{
				return r;
			}
		}
		return null;
	}

	private static KcRow kcRow(Plan plan, String bossId)
	{
		for (KcRow r : plan.kc)
		{
			if (r.bossId.equals(bossId))
			{
				return r;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ nothing changes until the list is added

	@Test
	public void theRecordsListWhatTheLedgerIsMissingAndNothingChangesUntilItIsAdded()
	{
		playedHere();
		ledger.setTracked("zulrah", "Magic fang", false);
		catchup.addKillLog(map("Zulrah", 150), catchup.stamp());
		catchup.addPage("Zulrah", map(), map("Tanzanite fang", 2, "Pet snakeling", 1, "Magic fang", 1), ZULRAH_PAGE, catchup.stamp());
		Plan plan = catchup.plan();
		// only listed
		assertEquals(100, ledger.kc("zulrah"));
		assertEquals(1, ledger.copyOf("zulrah").copies("Tanzanite fang"));
		assertEquals(1, plan.kc.size());
		KcRow k = plan.kc.get(0);
		assertEquals(100, k.cur);
		assertEquals(150, k.to);
		assertTrue(k.on);
		assertFalse(k.lower);
		// the player does not track the magic fang: it is not listed
		assertEquals(2, plan.items.size());
		assertEquals(1, row(plan, "zulrah", "Pet Snakeling").add); // the data's spelling of the game's "Pet snakeling"
		assertEquals(1, row(plan, "zulrah", "Tanzanite fang").add);
		assertNull(row(plan, "zulrah", "Magic fang"));
		assertTrue(Catchup.worthShowing(plan));
		assertEquals("Boss Kill Log, Collection Log (Zulrah): kill count Zulrah 100 → 150; Pet Snakeling +1, Tanzanite fang +1. Nothing changes until "
			+ "you add them.", Catchup.describe(plan));

		assertEquals("{1, 2, 0}", text(catchup.apply(plan)));
		BossState z = ledger.copyOf("zulrah");
		assertEquals(150, z.kc);
		assertEquals(2, z.copies("Tanzanite fang"));
		assertEquals(1, z.importedUnknown("Tanzanite fang"));
		assertEquals(1, z.copies("Pet Snakeling"));
		assertEquals(0, z.copies("Magic fang"));
		// the reads the list was made from are let go: nothing is left to offer
		assertFalse(Catchup.worthShowing(catchup.plan()));
		// looking at the page again lists nothing
		assertFalse(Catchup.worthShowing(pageOnly("Zulrah", map("Tanzanite fang", 2, "Pet snakeling", 1), ZULRAH_PAGE)));
		// the next kill on this computer follows on from the game's count
		capture.onKillCount("Zulrah", 151, 2000);
		assertEquals(151, ledger.kc("zulrah"));
	}

	@Test
	public void aCollectionLogPageSetsTheKillCountsItShows()
	{
		playedHere();
		// a page that shows every count of a boss sets it, up or down: what an earlier version raised from a page drawn with another
		// page's numbers comes back down
		capture.onShownCount("Araxxor kills", 81);
		catchup.addPage("Araxxor", map("Araxxor kills", 0), map(), names("Noxious pommel", "Araxyte fang"), catchup.stamp());
		Plan plan = catchup.plan();
		KcRow r = kcRow(plan, "araxxor");
		assertTrue(r.lower);
		assertEquals(81, r.cur);
		assertEquals(0, r.to);
		assertEquals("Collection Log (Araxxor): kill count Araxxor 81 → 0. Nothing changes until you add it.", Catchup.describe(plan));
		catchup.apply(plan);
		assertEquals(0, ledger.kc("araxxor"));
		// and up, with the kills it was missing
		catchup.addPage("Zulrah", map("Zulrah kills", 130), map("Tanzanite fang", 1), ZULRAH_PAGE, catchup.stamp());
		assertEquals(130, kcRow(catchup.plan(), "zulrah").to);
	}

	@Test
	public void aPageWithOnlySomeOfABosssCountsIsNotUsedForItsKillCount()
	{
		// a Desert Treasure II boss's page has no awakened count: whether its count holds the awakened kills is not known
		catchup.addPage("Duke Sucellus", map("Duke Sucellus kills", 40), map(), names(), catchup.stamp());
		assertTrue(catchup.plan().kc.isEmpty());
		// a page's lines that are not a boss's count change nothing
		catchup.addPage("Hallowed Sepulchre", map("Grand Hallowed Coffins opened", 12), map(), names(), catchup.stamp());
		assertFalse(Catchup.worthShowing(catchup.plan()));
	}

	@Test
	public void theLatestLookAtACountIsTheOneUsed()
	{
		catchup.addKillLog(map("Vorkath", 30), catchup.stamp());
		catchup.addPage("Vorkath", map("Vorkath kills", 32), map(), names(), catchup.stamp());
		assertEquals(32, kcRow(catchup.plan(), "vorkath").to);
		catchup.addKillLog(map("Vorkath", 33), catchup.stamp());
		assertEquals(33, kcRow(catchup.plan(), "vorkath").to);
	}

	@Test
	public void linesLeftUntickedAreNotAdded()
	{
		playedHere();
		catchup.addKillLog(map("Zulrah", 150), catchup.stamp());
		catchup.addPage("Zulrah", map(), map("Tanzanite fang", 2), ZULRAH_PAGE, catchup.stamp());
		Plan plan = catchup.plan();
		plan.kc.get(0).on = false;
		assertEquals("Boss Kill Log, Collection Log (Zulrah): Tanzanite fang +1. 1 more line to look at. Nothing changes until you add it.",
			Catchup.describe(plan));
		assertEquals("{0, 1, 0}", text(catchup.apply(plan)));
		assertEquals(100, ledger.kc("zulrah"));
		assertEquals(2, ledger.copyOf("zulrah").copies("Tanzanite fang"));
	}

	@Test
	public void dismissingForgetsWhatWasRead()
	{
		playedHere();
		catchup.addKillLog(map("Zulrah", 150), catchup.stamp());
		Plan plan = catchup.plan();
		catchup.clear();
		assertFalse(Catchup.worthShowing(catchup.plan()));
		// a list made before is not added after all
		assertEquals("{0, 0, 0}", text(catchup.apply(plan)));
		assertEquals(100, ledger.kc("zulrah"));
	}

	@Test
	public void recordsReadAfterAListWaitForTheNextOne()
	{
		playedHere();
		catchup.addKillLog(map("Zulrah", 150), catchup.stamp());
		Plan first = catchup.plan();
		catchup.addPage("Zulrah", map(), map("Tanzanite fang", 2), ZULRAH_PAGE, catchup.stamp());
		Plan both = catchup.plan();
		assertEquals("Boss Kill Log, Collection Log (Zulrah): kill count Zulrah 100 → 150; Tanzanite fang +1. Nothing changes until you add them.",
			Catchup.describe(both));
		// the first list is added: the page read after it stays for the next list
		catchup.apply(first);
		assertEquals(150, ledger.kc("zulrah"));
		assertEquals(1, ledger.copyOf("zulrah").copies("Tanzanite fang"));
		Plan next = catchup.plan();
		assertTrue(next.kc.isEmpty());
		assertEquals(1, next.items.size());
		catchup.apply(next);
		assertEquals(2, ledger.copyOf("zulrah").copies("Tanzanite fang"));
		assertFalse(Catchup.worthShowing(catchup.plan()));
	}

	@Test
	public void manyPagesAreNamedShortly()
	{
		for (String page : new String[]{"Zulrah", "Vorkath", "Kraken", "Cerberus"})
		{
			catchup.addPage(page, map(), map(), names(), catchup.stamp());
		}
		catchup.addKillLog(map("Vorkath", 30), catchup.stamp());
		assertEquals("Boss Kill Log, Collection Log (Zulrah, Vorkath and 2 more pages): kill count Vorkath 0 → 30. Nothing changes until you add it.",
			Catchup.describe(catchup.plan()));
	}

	@Test
	public void aBossThatChangedAfterTheListWasMadeKeepsWhatItHas()
	{
		playedHere();
		catchup.addKillLog(map("Zulrah", 150, "Vorkath", 30), catchup.stamp());
		catchup.addPage("Zulrah", map(), map("Tanzanite fang", 2), ZULRAH_PAGE, catchup.stamp());
		Plan plan = catchup.plan();
		// the next Zulrah kill comes, with a second fang, before the list is added
		capture.onKillCount("Zulrah", 151, 3000);
		capture.onLoot(new Capture.Loot("Zulrah", 1, Collections.singletonList(new Capture.Stack("Tanzanite fang", 1)), null, 3001));
		capture.onTick(5000, true);
		assertEquals(151, ledger.kc("zulrah"));
		// the kill-count message has already brought the 50 kills in; the list's older numbers would take one away
		assertEquals("{1, 0, 0, 2 left out}", text(catchup.apply(plan)));
		assertEquals(151, ledger.kc("zulrah"));
		assertEquals(2, ledger.copyOf("zulrah").copies("Tanzanite fang"));
		assertEquals(30, ledger.kc("vorkath"));
	}

	@Test
	public void aReadNoLongerSpeaksForABossThatChangedAfterIt()
	{
		playedHere();
		// the records match the ledger
		catchup.addKillLog(map("Zulrah", 100), catchup.stamp());
		catchup.addPage("Zulrah", map(), map("Tanzanite fang", 1), ZULRAH_PAGE, catchup.stamp());
		assertFalse(Catchup.worthShowing(catchup.plan()));
		// then a kill with a fang: their numbers are now behind the ledger's
		capture.onKillCount("Zulrah", 101, 3000);
		capture.onLoot(new Capture.Loot("Zulrah", 1, Collections.singletonList(new Capture.Stack("Tanzanite fang", 1)), null, 3001));
		capture.onTick(5000, true);
		// another page is read: the old reads offer neither a lower count nor to take the new fang away
		catchup.addPage("Vorkath", map(), map("Vorkath's head", 1), names("Vorki", "Vorkath's head"), catchup.stamp());
		Plan plan = catchup.plan();
		assertTrue(plan.kc.isEmpty());
		assertEquals(1, plan.items.size());
		assertEquals("vorkath", plan.items.get(0).bossId);
		// read again, the Boss Kill Log speaks for Zulrah once more
		catchup.addKillLog(map("Zulrah", 120), catchup.stamp());
		assertEquals(120, kcRow(catchup.plan(), "zulrah").to);
	}

	@Test
	public void aReadIsAsOldAsTheMomentTheGameSentIt()
	{
		playedHere();
		// the Boss Kill Log opens at kill 100; a kill lands before it is read
		Catchup.Stamp opened = catchup.stamp();
		capture.onKillCount("Zulrah", 101, 3000);
		catchup.addKillLog(map("Zulrah", 100, "Vorkath", 5), opened);
		Plan plan = catchup.plan();
		// it does not offer to take the new kill back off, and still speaks for the bosses that did not change
		assertNull(kcRow(plan, "zulrah"));
		assertEquals(5, kcRow(plan, "vorkath").to);
		assertEquals(101, ledger.kc("zulrah"));
		// a page drawn before a drop of one of its items is older than the ledger for that boss only
		Catchup.Stamp drawn = catchup.stamp();
		capture.onLoot(new Capture.Loot("Zulrah", 1, Collections.singletonList(new Capture.Stack("Tanzanite fang", 1)), null, 3001));
		capture.onTick(5000, true);
		assertFalse(catchup.unchangedSince(drawn, ZULRAH_PAGE, names()));
		assertTrue(catchup.unchangedSince(drawn, names("Vorki", "Vorkath's head", "Not an item"), names("Vorkath kills", "Not a count")));
		// nor for a count of it
		assertFalse(catchup.unchangedSince(drawn, names(), names("Zulrah kills")));
	}

	// ------------------------------------------------------------------ kill counts

	@Test
	public void aCountThatWentTooHighComesBackDownToTheGamesOnlyWhenAgreed()
	{
		// what an earlier version did with a page drawn with the previous page's numbers
		capture.onShownCount("Skotizo kills", 117);
		capture.onShownCount("Thermonuclear Smoke Devil kills", 5);
		assertEquals(117, ledger.kc("skotizo"));
		catchup.addKillLog(map("Skotizo", 5, "Thermonuclear smoke devil", 0), catchup.stamp());
		Plan plan = catchup.plan();
		KcRow sk = kcRow(plan, "skotizo");
		assertTrue(sk.lower);
		assertTrue(sk.on);
		assertEquals(117, sk.cur);
		assertEquals(5, sk.to);
		KcRow th = kcRow(plan, "thermonuclear-smoke-devil");
		assertEquals(0, th.to);
		assertEquals(117, ledger.kc("skotizo"));
		assertEquals("Boss Kill Log: kill counts Skotizo 117 → 5, Thermonuclear smoke devil 5 → 0. Nothing changes until you add them.",
			Catchup.describe(plan));
		catchup.apply(plan);
		assertEquals(5, ledger.kc("skotizo"));
		assertEquals(0, ledger.kc("thermonuclear-smoke-devil"));
		assertEquals(Integer.valueOf(5), ledger.copyOf("skotizo").labels().get("Skotizo"));
		// the next kill counts from the game's number
		capture.onKillCount("Skotizo", 6, 100);
		assertEquals(6, ledger.kc("skotizo"));
	}

	@Test
	public void aCountComesDownNoFurtherThanTheKillOfADropAndANoteSaysWhy()
	{
		capture.onShownCount("Skotizo kills", 117);
		ledger.logDrop("skotizo", "Dark claw", 50, 1, null, Drop.SRC_LIVE);
		Plan plan = killLogOnly("Skotizo", 5);
		KcRow sk = kcRow(plan, "skotizo");
		assertTrue(sk.lower);
		assertEquals(50, sk.to);
		assertEquals(5, sk.shown());
		assertEquals(Collections.singletonList("Skotizo: the game counts 5 kills, but the ledger has a drop at kill 50, so its kill count cannot go "
			+ "below that. If that kill number is wrong, change it on the boss's page."), plan.notes);
		catchup.apply(plan);
		assertEquals(50, ledger.kc("skotizo"));
		// with the drop at the ledger's own count, only the note is left
		Plan again = killLogOnly("Skotizo", 5);
		assertTrue(again.kc.isEmpty());
		assertTrue(Catchup.worthShowing(again));
		assertEquals("Boss Kill Log: Skotizo: the game counts 5 kills, but the ledger has a drop at kill 50, so its kill count cannot go below that. "
			+ "If that kill number is wrong, change it on the boss's page.", Catchup.describe(again));
	}

	@Test
	public void aCountTheLedgerSetByHandIsSetToTheGamesEitherWay()
	{
		ledger.setKc("zulrah", 90);
		Plan up = killLogOnly("Zulrah", 150);
		assertEquals(150, kcRow(up, "zulrah").to);
		catchup.apply(up);
		assertEquals(150, ledger.kc("zulrah"));
		assertEquals(Integer.valueOf(150), ledger.copyOf("zulrah").labels().get("Zulrah"));
		ledger.setKc("zulrah", 400);
		Plan down = killLogOnly("Zulrah", 150);
		assertTrue(kcRow(down, "zulrah").lower);
		catchup.apply(down);
		assertEquals(150, ledger.kc("zulrah"));
	}

	@Test
	public void theBossKillLogListsEveryCountAndTwoModesMakeOneLine()
	{
		playedHere();
		capture.onShownCount("Skotizo kills", 3);
		catchup.addKillLog(map("Zulrah", 1500, "Duke Sucellus", 40, "Awakened Duke Sucellus", 5, "Vorkath", 7, "Not a boss", 3, "Skotizo", 0,
			"Kraken", 0, "Callisto", 20, "Artio", 30), catchup.stamp());
		Plan plan = catchup.plan();
		assertEquals(6, plan.kc.size());
		assertEquals(45, kcRow(plan, "duke-sucellus").to);
		assertEquals(1500, kcRow(plan, "zulrah").to);
		assertEquals(7, kcRow(plan, "vorkath").to);
		assertEquals(20, kcRow(plan, "callisto").to);
		assertEquals(30, kcRow(plan, "artio").to);
		assertTrue(kcRow(plan, "skotizo").lower);
		assertNull("a boss with no kills in the game or the ledger", kcRow(plan, "kraken"));
		assertEquals("Boss Kill Log: kill counts Artio 0 → 30, Callisto 0 → 20, Duke Sucellus 0 → 45 and 3 more. Nothing changes until you add them.",
			Catchup.describe(plan));
		assertEquals(0, ledger.kc("vorkath"));
		catchup.apply(plan);
		assertEquals(1500, ledger.kc("zulrah"));
		assertEquals(45, ledger.kc("duke-sucellus"));
		assertEquals(7, ledger.kc("vorkath"));
		assertEquals(0, ledger.kc("skotizo"));
		catchup.addKillLog(map("Zulrah", 1500, "Duke Sucellus", 40, "Awakened Duke Sucellus", 5), catchup.stamp());
		assertFalse(Catchup.worthShowing(catchup.plan()));
	}

	@Test
	public void aBossKillLogWithSomeModesAddsTheKillsTheyShow()
	{
		capture.onKillCount("Theatre of Blood", 10, 0);
		capture.onKillCount("Theatre of Blood Hard Mode", 2, 200);
		assertEquals(12, ledger.kc("theatre-of-blood"));
		// no entry mode row: the two modes shown add their kills, once the last raid's chest is no longer awaited
		catchup.addKillLog(map("Theatre of Blood", 15, "Theatre of Blood: Hard Mode", 3), catchup.stamp());
		assertTrue(catchup.plan().kc.isEmpty());
		capture.onTick(200 + 3001, true); // a raid's chest is awaited for 3,000 ticks
		Plan plan = catchup.plan();
		KcRow t = kcRow(plan, "theatre-of-blood");
		assertEquals(12, t.cur);
		assertEquals(18, t.to);
		assertFalse(t.lower);
		catchup.apply(plan);
		assertEquals(18, ledger.kc("theatre-of-blood"));
		// and never take kills away
		assertTrue(killLogOnly("Theatre of Blood", 1, "Theatre of Blood: Hard Mode", 1).kc.isEmpty());
	}

	@Test
	public void aBossKillLogWithoutTheAwakenedKillsIsNotUsedForThatBoss()
	{
		capture.onKillCount("Duke Sucellus", 40, 0);
		capture.onKillCount("Awakened Duke Sucellus", 5, 200);
		capture.onTick(1000, true);
		// the one line may or may not hold the 5 awakened kills
		assertTrue(killLogOnly("Duke Sucellus", 50).kc.isEmpty());
		assertEquals(45, ledger.kc("duke-sucellus"));
	}

	@Test
	public void bossesWhoseRewardsAreClaimedAreLeftAlone()
	{
		assertTrue(killLogOnly("Sol Heredit", 12).kc.isEmpty());
	}

	// ------------------------------------------------------------------ copies the game does not show

	@Test
	public void copiesTheCollectionLogDoesNotHaveAreTakenAwayWhenAgreed()
	{
		playedHere(); // a fang seen at kill 50
		ledger.logDrop("zulrah", "Tanzanite fang", 80, 1, null, Drop.SRC_MANUAL);
		ledger.addOwned("zulrah", "Tanzanite fang", 1);
		ledger.logDrop("zulrah", "Magic fang", 60, 1, null, Drop.SRC_LIVE);
		Plan plan = pageOnly("Zulrah", map("Tanzanite fang", 1, "Pet snakeling", 1), ZULRAH_PAGE);
		assertEquals(2, row(plan, "zulrah", "Tanzanite fang").remove);
		assertTrue(row(plan, "zulrah", "Tanzanite fang").extra);
		assertEquals(1, row(plan, "zulrah", "Magic fang").remove);
		assertEquals(1, row(plan, "zulrah", "Pet Snakeling").add);
		assertEquals(3, ledger.copyOf("zulrah").copies("Tanzanite fang"));
		assertEquals("Collection Log (Zulrah): Magic fang −1, Pet Snakeling +1, Tanzanite fang −2. Nothing changes until you add them.", Catchup.describe(plan));
		assertEquals("{0, 1, 3}", text(catchup.apply(plan)));
		BossState z = ledger.copyOf("zulrah");
		// the copy imported with no kill number and the one typed in by hand go; the one seen in game stays
		assertEquals(1, z.copies("Tanzanite fang"));
		for (Drop d : z.drops)
		{
			if (d.item.equals("Tanzanite fang"))
			{
				assertEquals(Integer.valueOf(50), d.kc);
			}
		}
		assertEquals(0, z.copies("Magic fang"));
		assertFalse(Catchup.worthShowing(pageOnly("Zulrah", map("Tanzanite fang", 1, "Pet snakeling", 1), ZULRAH_PAGE)));
	}

	@Test
	public void aPageThatDrawsEveryItemFadedTakesNothingAway()
	{
		playedHere();
		ledger.logDrop("zulrah", "Tanzanite fang", 80, 1, null, Drop.SRC_MANUAL);
		// it may have been drawn before its numbers came
		assertFalse(Catchup.worthShowing(pageOnly("Zulrah", map(), ZULRAH_PAGE)));
	}

	@Test
	public void stackedItemsPetsUntrackedItemsAndCountsThatMayBeCappedAreNotTakenAway()
	{
		// waystones drop one or two at a time: the game counts waystones, not drops
		ledger.logDrop("doom-of-mokhaiotl", "Mokhaiotl waystone", 10, 1, null, Drop.SRC_LIVE);
		ledger.logDrop("doom-of-mokhaiotl", "Mokhaiotl waystone", 20, 1, null, Drop.SRC_LIVE);
		ledger.logDrop("doom-of-mokhaiotl", "Mokhaiotl waystone", 30, 1, null, Drop.SRC_LIVE);
		assertFalse(Catchup.worthShowing(pageOnly("Doom of Mokhaiotl", map("Mokhaiotl waystone", 2), names("Mokhaiotl waystone"))));
		// the ledger counts a pet that came again while the player had it; the game may not
		ledger.logDrop("zulrah", "Pet Snakeling", 30, 1, null, Drop.SRC_LIVE);
		ledger.logDrop("zulrah", "Pet Snakeling", 40, 1, null, Drop.SRC_LIVE);
		// and the player has stopped tracking the magic fang
		ledger.logDrop("zulrah", "Magic fang", 50, 1, null, Drop.SRC_LIVE);
		ledger.setTracked("zulrah", "Magic fang", false);
		assertFalse(Catchup.worthShowing(pageOnly("Zulrah", map("Pet snakeling", 1), ZULRAH_PAGE)));
		// the collection log stops counting some items at a cap; 250 or more may be one
		ledger.addOwned("sarachnis", "Giant egg sac(full)", 260);
		assertFalse(Catchup.worthShowing(pageOnly("Sarachnis", map("Giant egg sac(full)", 250), names("Giant egg sac(full)"))));
		Plan plan = pageOnly("Sarachnis", map("Giant egg sac(full)", 249), names("Giant egg sac(full)"));
		assertEquals(11, row(plan, "sarachnis", "Giant egg sac(full)").remove);
		catchup.apply(plan);
		assertEquals(249, ledger.copyOf("sarachnis").copies("Giant egg sac(full)"));
	}

	@Test
	public void itemsThePluginHidesAreLeftAlone()
	{
		// the ancient shards Skotizo gives every kill are not tracked
		Plan plan = pageOnly("Skotizo", map("Ancient shard", 140, "Dark claw", 1), SKOTIZO_PAGE);
		assertNull(row(plan, "skotizo", "Ancient shard"));
		assertEquals(1, row(plan, "skotizo", "Dark claw").add);
	}

	@Test
	public void anItemSeveralBossesHoldLosesImportedCopiesFirstAndIsOtherwisePointedOut()
	{
		// a dragon pickaxe seen at the Kalphite Queen, and one an import put at Callisto with no kill number
		ledger.logDrop("kalphite-queen", "Dragon pickaxe", 10, 1, null, Drop.SRC_LIVE);
		ledger.addOwned("callisto", "Dragon pickaxe", 1);
		Plan plan = pageOnly("Kalphite Queen", map("Dragon pickaxe", 1), names("Kalphite princess", "Dragon pickaxe"));
		ItemRow r = row(plan, "callisto", "Dragon pickaxe");
		assertTrue(r.importedOnly);
		assertEquals(1, r.remove);
		assertNull(row(plan, "kalphite-queen", "Dragon pickaxe"));
		catchup.apply(plan);
		assertEquals(1, ledger.copyOf("kalphite-queen").copies("Dragon pickaxe"));
		assertEquals(0, ledger.copyOf("callisto").copies("Dragon pickaxe"));
		// one seen in game at each of two bosses, and the game counts one: which is wrong is not known
		ledger.logDrop("general-graardor", "Godsword shard 1", 5, 1, null, Drop.SRC_LIVE);
		ledger.logDrop("kreearra", "Godsword shard 1", 7, 1, null, Drop.SRC_LIVE);
		Plan shards = pageOnly("General Graardor", map("Godsword shard 1", 1), names("Bandos hilt", "Godsword shard 1"));
		assertTrue(shards.items.isEmpty());
		assertEquals(Collections.singletonList("The ledger has 1 more Godsword shard 1 than the collection log shows, split between General Graardor and "
			+ "Kree'arra: remove the wrong copy on its boss page."), shards.notes);
		assertTrue(Catchup.worthShowing(shards));
		assertEquals(0, catchup.apply(shards)[2]);
		assertEquals(1, ledger.copyOf("general-graardor").copies("Godsword shard 1"));
		assertEquals(1, ledger.copyOf("kreearra").copies("Godsword shard 1"));
	}

	@Test
	public void anItemThePageDoesNotPinToOneBossIsListedButNotTicked()
	{
		// both bears drop the ring, and the page is the bears' page
		ledger.setKc("callisto", 20);
		ledger.setKc("artio", 30);
		Plan plan = pageOnly("Callisto and Artio", map("Tyrannical ring", 1), names("Callisto cub", "Tyrannical ring"));
		assertEquals(1, plan.items.size());
		assertTrue(plan.items.get(0).assumed);
		assertFalse(plan.items.get(0).on);
		assertEquals("Collection Log (Callisto and Artio): 1 line to look at. Review it to choose what to add.", Catchup.describe(plan));
		catchup.apply(plan);
		assertEquals(0, ledger.copyOf("callisto").copies("Tyrannical ring"));
		assertEquals(0, ledger.copyOf("artio").copies("Tyrannical ring"));
	}

	// ------------------------------------------------------------------ the words

	@Test
	public void theNoteShortensLongListsAndSaysWhatIsLeftToLookAt()
	{
		Plan p = new Plan();
		p.sources.add("Boss Kill Log");
		for (String id : new String[]{"zulrah", "vorkath", "kraken", "cerberus", "sarachnis"})
		{
			p.kc.add(new KcRow(id, data.boss(id).name, 0, 2, map()));
		}
		assertEquals("Boss Kill Log: kill counts Zulrah 0 → 2, Vorkath 0 → 2, Kraken 0 → 2 and 2 more. Nothing changes until you add them.", Catchup.describe(p));
		Plan items = new Plan();
		items.sources.add("X");
		for (String item : new String[]{"A", "B", "C", "D", "E"})
		{
			items.items.add(new ItemRow("zulrah", "Zulrah", item, 1, 0, 0, 200, false, 0));
		}
		assertEquals("X: A +1, B +1, C +1 and 2 more items. Nothing changes until you add them.", Catchup.describe(items));
		Plan unticked = new Plan();
		unticked.sources.add("X");
		unticked.items.add(new ItemRow("zulrah", "Zulrah", "A", 1, 0, 0, 200, true, 0));
		unticked.items.get(0).on = false;
		assertEquals("X: 1 line to look at. Review it to choose what to add.", Catchup.describe(unticked));
		Plan nothing = new Plan();
		nothing.sources.add("X");
		nothing.kc.add(new KcRow("zulrah", "Zulrah", 5, 5, map()));
		assertFalse(Catchup.worthShowing(nothing));
		nothing.notes.add("One.");
		nothing.notes.add("Two.");
		assertTrue(Catchup.worthShowing(nothing));
		assertEquals("X: One. Review it for 1 more note.", Catchup.describe(nothing));
	}

	private static String text(int[] a)
	{
		return "{" + a[0] + ", " + a[1] + ", " + a[2] + (a[3] > 0 ? ", " + a[3] + " left out" : "") + "}";
	}
}
