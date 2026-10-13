package com.dropluckledger.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.dropluckledger.TestData;
import com.dropluckledger.capture.Capture.Loot;
import com.dropluckledger.capture.Capture.Stack;
import com.dropluckledger.engine.Data;
import com.dropluckledger.ledger.BossState;
import com.dropluckledger.ledger.Ledger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/**
 * Bosses whose loot does not simply arrive once per kill: rewards taken later (Tempoross), runs that only show as loot (Doom of
 * Mokhaiotl), loot that goes to the top damage dealer (God Wars Dungeon, Corporeal Beast...), the bodyguards' loot, and settings the
 * game reveals per kill (the reward taken, the brothers killed, a skill level).
 */
public class GroupCaptureTest
{
	private final Data data = TestData.get();
	private final BossIndex index = new BossIndex(data);
	private Ledger ledger;
	private Capture capture;
	private final List<String> logged = new ArrayList<>();

	@Before
	public void setUp()
	{
		ledger = new Ledger(data, () -> 1_000L);
		capture = new Capture(ledger, index);
		capture.setListener((boss, item, kill) -> logged.add(boss + ":" + item + "@" + kill));
		capture.onTick(0, true);
	}

	private void kc(String label, int n, int tick)
	{
		capture.onKillCount(label, n, tick);
	}

	private static Loot loot(String name, int tick, String... items)
	{
		return lootWith(name, tick, null, items);
	}

	private static Loot lootWith(String name, int tick, Map<String, Object> hints, String... items)
	{
		List<Stack> st = new ArrayList<>();
		for (String i : items)
		{
			st.add(new Stack(i, 1));
		}
		return new Loot(name, 1, st, hints, tick);
	}

	private BossState boss(String id)
	{
		return ledger.copyOf(id);
	}

	/**
	 * The value of a setting for one kill (the boss's default where no period says).
	 */
	private Object param(String id, int kill, String name)
	{
		Object v = boss(id).paramsAt(kill).get(name);
		return v != null ? v : com.dropluckledger.engine.Engine.defaultParams(data.boss(id)).get(name);
	}

	private static int num(Object v)
	{
		return v == null ? -1 : ((Number) v).intValue();
	}

	// ------------------------------------------------------------------ rewards taken later

	@Test
	public void rewardPoolLootDoesNotCountAsGames()
	{
		ledger.setTracked("tempoross", "Big harpoonfish", true);
		kc("Tempoross", 10, 100);
		assertEquals(10, boss("tempoross").kc);
		// the permits of several games are fished at once: none of it is a game of its own
		capture.onLoot(loot("Reward pool (Tempoross)", 150, "Raw harpoonfish"));
		capture.onLoot(loot("Reward pool (Tempoross)", 152, "Big harpoonfish"));
		capture.onLoot(loot("Reward pool (Tempoross)", 154, "Soaked page"));
		BossState t = boss("tempoross");
		assertEquals(10, t.kc);
		assertEquals(0, t.provisional());
		assertEquals(1, t.copies("Big harpoonfish"));
		assertEquals(Integer.valueOf(10), t.drops.get(0).kc);
		kc("Tempoross", 11, 900);
		assertEquals(11, boss("tempoross").kc);
	}

	// ------------------------------------------------------------------ runs that only show as loot

	@Test
	public void eachClaimedDoomPileIsOneRunAtItsDelve()
	{
		capture.onLoot(lootWith("Doom of Mokhaiotl", 100, Collections.singletonMap("delve", 6), "Mokhaiotl cloth"));
		capture.onLoot(lootWith("Doom of Mokhaiotl", 400, Collections.singletonMap("delve", 3), "Demon tear"));
		BossState d = boss("doom-of-mokhaiotl");
		assertEquals(2, d.kc);
		assertEquals(0, d.provisional());
		assertEquals(6, num(param("doom-of-mokhaiotl", 1, "delve")));
		assertEquals(3, num(param("doom-of-mokhaiotl", 2, "delve")));
		assertEquals(1, d.copies("Mokhaiotl cloth"));
		// a kill-count message for the boss does not count runs twice
		kc("Doom of Mokhaiotl", 40, 500);
		assertEquals(2, boss("doom-of-mokhaiotl").kc);
	}

	// ------------------------------------------------------------------ loot to the top damage dealer

	@Test
	public void aTeamKillWhoseLootWentElsewhereIsLeftOut()
	{
		kc("General Graardor", 50, 100);
		capture.onLoot(loot("General Graardor", 103, "Bandos tassets"));
		kc("General Graardor", 51, 200);
		// no loot for kill 51: another player dealt the most damage
		capture.onTick(200 + Capture.TOP_WINDOW + 1, true);
		BossState g = boss("general-graardor");
		assertEquals(51, g.kc);
		assertEquals(100, num(param("general-graardor", 50, "looted")));
		assertEquals(0, num(param("general-graardor", 51, "looted")));
		// the next kill pays again
		kc("General Graardor", 52, 300);
		capture.onLoot(loot("General Graardor", 302, "Coins"));
		g = boss("general-graardor");
		assertEquals(100, num(param("general-graardor", 52, "looted")));
		assertEquals(0, num(param("general-graardor", 51, "looted")));
		assertEquals(1, g.copies("Bandos tassets"));
	}

	@Test
	public void theNextKillDecidesWhenTheLootNeverCame()
	{
		kc("Dagannoth Rex", 10, 100);
		kc("Dagannoth Rex", 11, 130); // a quick second kill with no loot from the first
		capture.onLoot(loot("Dagannoth Rex", 133, "Coins"));
		assertEquals(0, num(param("dagannoth-rex", 10, "looted")));
		assertEquals(100, num(param("dagannoth-rex", 11, "looted")));
	}

	@Test
	public void lateLootUndoesTheMark()
	{
		kc("Kalphite Queen", 5, 100);
		capture.onTick(100 + Capture.TOP_WINDOW + 1, true);
		assertEquals(0, num(param("kalphite-queen", 5, "looted")));
		capture.onLoot(loot("Kalphite Queen", 100 + Capture.TOP_WINDOW + 5, "Coins", "Kq head"));
		BossState q = boss("kalphite-queen");
		assertEquals(5, q.kc);
		assertEquals(100, num(param("kalphite-queen", 5, "looted")));
		assertEquals(0, q.provisional());
	}

	@Test
	public void nothingIsDecidedWithTheLootTrackerOff()
	{
		capture.onTick(50, false);
		kc("Corporeal Beast", 20, 100);
		capture.onTick(100 + Capture.TOP_WINDOW + 1, false);
		kc("Corporeal Beast", 21, 300);
		BossState c = boss("corporeal-beast");
		assertEquals(100, num(param("corporeal-beast", 20, "looted")));
		assertEquals(1, c.segs.size());
	}

	@Test
	public void ordinaryBossesNeverMarkKills()
	{
		kc("Zulrah", 10, 100);
		capture.onTick(100 + Capture.TOP_WINDOW + 1, true);
		kc("Zulrah", 11, 200);
		assertEquals(1, boss("zulrah").segs.size());
	}

	// ------------------------------------------------------------------ the God Wars bodyguards

	@Test
	public void bodyguardLootIsRecordedUnderTheGeneralWithoutAKill()
	{
		kc("General Graardor", 30, 100);
		capture.onLoot(loot("General Graardor", 102, "Coins"));
		capture.onLoot(loot("Sergeant Strongstack", 140, "Godsword shard 2"));
		capture.onLoot(loot("Sergeant Steelwill", 150, "Coins"));
		BossState g = boss("general-graardor");
		assertEquals(30, g.kc);
		assertEquals(0, g.provisional());
		assertEquals(1, g.copies("Godsword shard 2"));
		assertEquals(Integer.valueOf(30), g.drops.get(0).kc);
		// and a bodyguard's loot never stands for the general's own loot of the next kill
		kc("General Graardor", 31, 300);
		capture.onLoot(loot("Sergeant Grimspike", 302, "Coins"));
		capture.onTick(300 + Capture.TOP_WINDOW + 1, true);
		assertEquals(0, num(param("general-graardor", 31, "looted")));
	}

	@Test
	public void anUnsiredOfferedLaterIsTheSiresLootWithoutAKill()
	{
		kc("Abyssal Sire", 300, 100);
		capture.onLoot(loot("Abyssal Sire", 103, "Unsired", "Coins"));
		// offered at the Font of Consumption half an hour later
		capture.onLoot(loot("Unsired", 4000, "Bludgeon claw"));
		BossState s = boss("abyssal-sire");
		assertEquals(300, s.kc);
		assertEquals(0, s.provisional());
		assertEquals(1, s.copies("Bludgeon claw"));
		assertEquals(100, num(param("abyssal-sire", 300, "looted")));
	}

	// ------------------------------------------------------------------ the reward taken

	@Test
	public void theMaggotKingsRewardIsTheLootOrTheEggs()
	{
		kc("Maggot King", 7, 100);
		capture.onLoot(loot("Maggot King", 104, "Coins"));
		kc("Maggot King", 8, 200);
		// no loot: the eggs were taken
		capture.onTick(200 + Capture.TOP_WINDOW + 1, true);
		BossState m = boss("maggot-king");
		assertEquals(8, m.kc);
		assertEquals(0, m.provisional());
		assertEquals("stomach", param("maggot-king", 7, "choice"));
		assertEquals("eggs", param("maggot-king", 8, "choice"));
		assertNull(m.seenFrom("ca"));
	}

	@Test
	public void aDestroyedCorpseIsAKillWithoutLoot()
	{
		kc("Araxxor", 60, 100);
		capture.onLoot(loot("Araxxor", 104, "Coins"));
		kc("Araxxor", 61, 200);
		capture.onTick(200 + Capture.TOP_WINDOW + 1, true);
		kc("Araxxor", 62, 300);
		capture.onLoot(loot("Araxxor", 303, "Clue scroll (elite)")); // a clue still rolls on a destroyed corpse
		assertEquals(0, num(param("araxxor", 60, "destroy")));
		assertEquals(1, num(param("araxxor", 61, "destroy")));
		assertEquals(1, num(param("araxxor", 62, "destroy")));
		kc("Araxxor", 63, 400);
		capture.onLoot(loot("Araxxor", 403, "Coins", "Araxyte fang"));
		assertEquals(0, num(param("araxxor", 63, "destroy")));
		assertEquals(63, boss("araxxor").kc);
	}

	// ------------------------------------------------------------------ what the game revealed, and the kills before

	@Test
	public void theFirstKillEachSettingWasReadForIsRemembered()
	{
		ledger.setKc("hespori", 40);
		kc("Hespori", 41, 100);
		capture.onLoot(lootWith("Hespori", 102, Collections.singletonMap("farming", 70), "Coins"));
		kc("Hespori", 42, 200);
		capture.onLoot(lootWith("Hespori", 202, Collections.singletonMap("farming", 72), "Bottomless compost bucket"));
		BossState h = boss("hespori");
		assertEquals(42, h.kc);
		assertEquals(Integer.valueOf(41), h.seenFrom("farming"));
		// the kills before take the first level read: a better guess than the default
		assertEquals(70, num(param("hespori", 1, "farming")));
		assertEquals(70, num(param("hespori", 40, "farming")));
		assertEquals(72, num(param("hespori", 42, "farming")));
		// what the player sets afterwards is for those kills only
		ledger.setParamBefore("hespori", "farming", 60);
		assertEquals(60, num(param("hespori", 40, "farming")));
		assertEquals(70, num(param("hespori", 41, "farming")));
		assertEquals(72, num(param("hespori", 42, "farming")));
	}

	@Test
	public void aValueThePlayerSetForTheEarlierKillsIsKept()
	{
		ledger.setKc("hespori", 200);
		ledger.setParamBefore("hespori", "farming", 80); // nothing read yet: every kill
		kc("Hespori", 201, 100);
		capture.onLoot(lootWith("Hespori", 102, Collections.singletonMap("farming", 90), "Coins"));
		assertEquals(80, num(param("hespori", 200, "farming")));
		assertEquals(90, num(param("hespori", 201, "farming")));
		assertEquals(Integer.valueOf(201), boss("hespori").seenFrom("farming"));
	}

	@Test
	public void whatTheLootToldMovesWithAKillCountedFromIt()
	{
		// the chest's loot is seen before the game's count: the kill is counted from the loot, then moved to the game's number
		capture.onLoot(lootWith("Barrows", 100, Collections.singletonMap("brothers", 5), "Coins"));
		kc("Barrows Chest", 200, 101);
		BossState b = boss("barrows");
		assertEquals(200, b.kc);
		assertEquals(Integer.valueOf(200), b.seenFrom("brothers"));
		assertEquals(Integer.valueOf(200), b.liveFrom);
		assertEquals(5, num(param("barrows", 200, "brothers")));
	}

	@Test
	public void perKillFactsAreNotGuessedForTheEarlierKills()
	{
		ledger.setKc("general-graardor", 80);
		kc("General Graardor", 81, 100);
		capture.onTick(100 + Capture.TOP_WINDOW + 1, true);
		BossState g = boss("general-graardor");
		assertEquals(Integer.valueOf(81), g.seenFrom("looted"));
		assertEquals(0, num(param("general-graardor", 81, "looted")));
		assertEquals(100, num(param("general-graardor", 80, "looted")));
	}

	// ------------------------------------------------------------------ the game's names for items

	@Test
	public void theGamesNameForAWikiDisambiguatedItemIsMatched()
	{
		assertEquals("Casket (Reward pool)", index.item("tempoross", "Casket"));
		assertEquals("Tooth half of key (moon key)", index.item("the-hueycoatl", "Tooth half of key"));
		assertEquals("general-graardor", index.loot("Sergeant Strongstack"));
		assertTrue(index.isExtraLoot("general-graardor", "Sergeant Strongstack"));
	}

	@Test
	public void theBossesThePluginLeavesOutAreNotKnown()
	{
		assertNull(index.kc("Chambers of Xeric"));
		assertNull(index.kc("Theatre of Blood"));
		assertNull(index.kc("Tombs of Amascut: Expert Mode"));
		assertNull(index.kc("Royal Titans"));
		assertNull(index.kc("Wintertodt"));
		assertNull(index.loot("Branda the Fire Queen"));
		assertNull(index.loot("Reward cart (Wintertodt)"));
		kc("Royal Titans", 10, 100);
		capture.onLoot(loot("Tombs of Amascut", 102, "Coins"));
		assertTrue(ledger.activeBosses().isEmpty());
	}

	@Test
	public void itemsThePluginHidesAreNeverRecorded()
	{
		ledger.setTracked("barrows", "Bolt rack", true); // a ledger from before they were hidden can list them
		kc("Barrows Chest", 10, 100);
		capture.onLoot(loot("Barrows", 101, "Bolt rack", "Ahrim's hood"));
		BossState b = boss("barrows");
		assertEquals(10, b.kc);
		assertEquals(0, b.copies("Bolt rack"));
		assertEquals(1, b.copies("Ahrim's hood"));
		assertFalse(ledger.trackedNames("barrows").contains("Bolt rack"));
		assertFalse(ledger.trackedNames("amoxliatl").contains("Frozen tear"));
		assertFalse(ledger.trackedNames("the-hueycoatl").contains("Tooth half of key (moon key)"));
	}

	@Test
	public void aScrollBoxIsTheClueScrollItReplaces()
	{
		assertEquals("Clue scroll (elite)", index.item("vorkath", "Scroll box (elite)"));
		assertEquals("Clue scroll (beginner)", index.item("brutus", "Scroll box (beginner)"));
		assertNull(index.item("vorkath", "Scroll box (master)"));
		ledger.setTracked("vorkath", "Clue scroll (elite)", true);
		kc("Vorkath", 20, 100);
		capture.onLoot(loot("Vorkath", 102, "Dragon bones", "Scroll box (elite)"));
		assertEquals(1, boss("vorkath").copies("Clue scroll (elite)"));
	}

	@Test
	public void theFirstKillThePluginSawIsRemembered()
	{
		kc("Zulrah", 300, 100);
		assertEquals(Integer.valueOf(300), boss("zulrah").liveFrom);
		kc("Zulrah", 301, 200);
		assertEquals(Integer.valueOf(300), boss("zulrah").liveFrom);
	}
}
