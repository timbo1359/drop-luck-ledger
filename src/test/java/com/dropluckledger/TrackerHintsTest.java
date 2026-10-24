package com.dropluckledger;

import static org.junit.Assert.assertEquals;

import com.dropluckledger.capture.BossIndex;
import com.dropluckledger.capture.Capture;
import com.dropluckledger.engine.Data;
import com.dropluckledger.engine.Engine;
import com.dropluckledger.ledger.BossState;
import com.dropluckledger.plugin.FakeGame;
import com.dropluckledger.plugin.TrackingState;
import com.google.gson.Gson;
import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.util.Filepath;
import net.runelite.http.api.loottracker.LootRecordType;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * What the plugin reads from the game about a kill along with it: the brothers or moons defeated, the wave, the MVP, whose loot it
 * was, the kind of world.
 */
public class TrackerHintsTest
{
	private static final int COINS = 995;
	private static final int AHRIM_HOOD = 4708;
	private static final int BLUE_SPEAR = 28988;
	private static final int RAT_MEAT = 2134;
	private static final int BIG_BONES = 532;
	private static final int MOOLETA = 31000;
	private static final int BANDOS_TASSETS = 11834;
	private static final int SPLINTERS = 28924;
	private static final int QUIVER = 28947;
	private static final int BLIGHTED_RESTORE = 24598;
	private static final int BLIGHTED_MANTA = 24589;
	private static final int ETHER = 21820;
	private static final int TWISTED_BOW = 20997;
	private static final int SCYTHE = 22486;
	private static final int FANG = 26219;

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private Tracker tracker;
	private FakeGame game;
	private ScheduledExecutorService executor;
	private File data;

	@Before
	public void setUp() throws Exception
	{
		data = tmp.newFolder("plugin-data");
		game = new FakeGame().addItem(COINS, "Coins").addItem(AHRIM_HOOD, "Ahrim's hood").addItem(BLUE_SPEAR, "Blue moon spear")
			.addItem(RAT_MEAT, "Raw rat meat").addItem(BIG_BONES, "Big bones")
			.addItem(MOOLETA, "Mooleta").addItem(BANDOS_TASSETS, "Bandos tassets").addItem(SPLINTERS, "Sunfire splinters")
			.addItem(QUIVER, "Dizana's quiver (uncharged)").addItem(BLIGHTED_RESTORE, "Blighted super restore(4)")
			.addItem(BLIGHTED_MANTA, "Blighted manta ray").addItem(ETHER, "Revenant ether").addItem(TWISTED_BOW, "Twisted bow")
			.addItem(SCYTHE, "Scythe of vitur (uncharged)").addItem(FANG, "Osmumten's fang");
		executor = Executors.newSingleThreadScheduledExecutor();
		Filepath dir = TestFiles.root(data.toPath()).joinSegment("drop-luck-ledger").rooted();
		tracker = new Tracker(game, new DropLuckLedgerConfig()
		{
		}, dir, new Gson(), executor);
		tracker.start();
		waitFor("the boss data", () -> tracker.core() != null);
		game.logIn(42);
		tracker.onGameStateChanged(GameState.LOGGED_IN);
		waitFor("tracking", () -> tracker.state() == TrackingState.TRACKING);
	}

	@After
	public void tearDown()
	{
		tracker.stop();
		executor.shutdownNow();
	}

	private void drain() throws Exception
	{
		for (int i = 0; i < 3; i++)
		{
			executor.submit(() ->
			{
			}).get(10, TimeUnit.SECONDS);
			SwingUtilities.invokeAndWait(() ->
			{
			});
		}
	}

	private void waitFor(String what, BooleanSupplier ok) throws Exception
	{
		Wait.until(what, ok);
		drain();
	}

	private void say(String text)
	{
		say(ChatMessageType.GAMEMESSAGE, text);
	}

	private void say(ChatMessageType type, String text)
	{
		game.tick++;
		tracker.onChatMessage(type, text);
	}

	private void loot(LootRecordType type, String name, Object metadata, int... idsAndQuantities)
	{
		int[] ids = new int[idsAndQuantities.length / 2];
		int[] quantities = new int[idsAndQuantities.length / 2];
		for (int i = 0; i < ids.length; i++)
		{
			ids[i] = idsAndQuantities[i * 2];
			quantities[i] = idsAndQuantities[i * 2 + 1];
		}
		game.tick++;
		tracker.onLoot(type, name, 1, ids, quantities, metadata);
	}

	private void ticks(int n)
	{
		for (int i = 0; i < n; i++)
		{
			game.tick++;
			tracker.onGameTick();
		}
	}

	private BossState boss(String id)
	{
		return tracker.core().ledger.copyOf(id);
	}

	private Object param(String id, int kill, String name)
	{
		Object v = boss(id).paramsAt(kill).get(name);
		return v != null ? v : Engine.defaultParams(tracker.core().data.boss(id)).get(name);
	}

	private static int num(Object v)
	{
		return ((Number) v).intValue();
	}

	@Test
	public void eachRaidsSettingsAreReadAtItsChest()
	{
		// Chambers of Xeric: challenge mode from the count, the player's points from the game
		game.varps.put(VarPlayerID.RAIDS_PLAYERSCORE, 47_500);
		say("Your completed Chambers of Xeric Challenge Mode count is: <col=ff0000>12</col>.");
		game.tick += 300; // the chest is looted a few minutes later
		loot(LootRecordType.EVENT, "Chambers of Xeric", null, TWISTED_BOW, 1, COINS, 10);
		assertEquals(12, boss("chambers-of-xeric").kc);
		assertEquals(Integer.valueOf(12), boss("chambers-of-xeric").drops.get(0).kc);
		assertEquals(1, num(param("chambers-of-xeric", 12, "cm")));
		assertEquals(47_500, num(param("chambers-of-xeric", 12, "points")));
		// the raids before keep the usual points until the player sets theirs; the count is of challenge mode raids, so they all were
		assertEquals(30_000, num(param("chambers-of-xeric", 11, "points")));
		assertEquals(1, num(param("chambers-of-xeric", 11, "cm")));

		// Theatre of Blood: the mode from the count, the team from the party
		game.varbits.put(VarbitID.TOB_CLIENT_P0, 1);
		game.varbits.put(VarbitID.TOB_CLIENT_P1, 2);
		game.varbits.put(VarbitID.TOB_CLIENT_P2, 1);
		say("Your completed Theatre of Blood: Hard Mode count is: <col=ff0000>40</col>.");
		game.tick += 200;
		loot(LootRecordType.EVENT, "Theatre of Blood", null, SCYTHE, 1);
		assertEquals("hard", param("theatre-of-blood", 40, "mode"));
		assertEquals(3, num(param("theatre-of-blood", 40, "team")));
		assertEquals(4, num(param("theatre-of-blood", 39, "team")));
		assertEquals(1, boss("theatre-of-blood").copies("Scythe of Vitur (uncharged)")); // the data's spelling of the game's name

		// Tombs of Amascut: the raid level the Loot Tracker gives
		say("Your completed Tombs of Amascut: Expert Mode count is: <col=ff0000>8</col>.");
		game.tick += 200;
		loot(LootRecordType.EVENT, "Tombs of Amascut", new int[] {425, 4, 31_000}, FANG, 1);
		assertEquals(425, num(param("tombs-of-amascut", 8, "raidLevel")));
		assertEquals(300, num(param("tombs-of-amascut", 7, "raidLevel")));
		assertEquals(1, boss("tombs-of-amascut").copies("Osmumten's fang"));
	}

	@Test
	public void theBrothersKilledAreReadAtTheChest()
	{
		game.varbits.put(VarbitID.BARROWS_KILLED_AHRIM, 1);
		game.varbits.put(VarbitID.BARROWS_KILLED_DHAROK, 1);
		game.varbits.put(VarbitID.BARROWS_KILLED_KARIL, 1);
		game.varbits.put(VarbitID.BARROWS_KILLED_VERAC, 1);
		say("Your Barrows chest count is: <col=ff0000>120</col>.");
		loot(LootRecordType.EVENT, "Barrows", null, AHRIM_HOOD, 1, COINS, 400);
		assertEquals(4, num(param("barrows", 120, "brothers")));
		assertEquals(1, boss("barrows").copies("Ahrim's hood"));
	}

	@Test
	public void theMoonsDefeatedAreReadAtTheLunarChest()
	{
		game.varbits.put(VarbitID.PMOON_BOSS_BLOOD_DEAD, 1);
		game.varbits.put(VarbitID.PMOON_BOSS_BLUE_DEAD, 1);
		say("Your Lunar Chest count is: <col=ff0000>33</col>.");
		loot(LootRecordType.EVENT, "Lunar Chest", null, BLUE_SPEAR, 1);
		assertEquals(1, num(param("moons-of-peril", 33, "blue")));
		assertEquals(1, num(param("moons-of-peril", 33, "blood")));
		assertEquals(0, num(param("moons-of-peril", 33, "eclipse")));
		assertEquals(1, boss("moons-of-peril").copies("Blue Moon spear"));
	}

	@Test
	public void theMvpIsReadFromTheLoot()
	{
		say("Your Scurrius kill count is: <col=ff0000>5</col>.");
		loot(LootRecordType.NPC, "Scurrius", null, COINS, 1000, RAT_MEAT, 1);
		say("Your Scurrius kill count is: <col=ff0000>6</col>.");
		loot(LootRecordType.NPC, "Scurrius", null, COINS, 300);
		assertEquals(1, num(param("scurrius", 5, "mvp")));
		assertEquals(0, num(param("scurrius", 6, "mvp")));
		say("Your Nex kill count is: <col=ff0000>40</col>.");
		loot(LootRecordType.NPC, "Nex", null, BIG_BONES, 1, COINS, 5000);
		assertEquals(1, num(param("nex", 40, "mvp")));
	}

	@Test
	public void eachColosseumClaimIsARunAtTheWaveItWasClaimedAt()
	{
		for (int w = 1; w <= 7; w++)
		{
			say("Wave " + w + " completed! Wave duration: 1:0" + w + ".20");
		}
		loot(LootRecordType.EVENT, "Fortis Colosseum", null, SPLINTERS, 1500);
		for (int w = 1; w <= 12; w++)
		{
			say("Wave " + w + " completed! Wave duration: 2:0" + (w % 10) + ".40");
		}
		say("Your Sol Heredit kill count is: <col=ff0000>5</col>.");
		loot(LootRecordType.EVENT, "Fortis Colosseum", null, SPLINTERS, 4000, QUIVER, 1);
		// a claim without a wave message (the plugin started mid-run) keeps the wave of the run before
		loot(LootRecordType.EVENT, "Fortis Colosseum", null, SPLINTERS, 900);
		BossState f = boss("fortis-colosseum");
		assertEquals(3, f.kc);
		assertEquals(7, num(param("fortis-colosseum", 1, "waves")));
		assertEquals(12, num(param("fortis-colosseum", 2, "waves")));
		assertEquals(12, num(param("fortis-colosseum", 3, "waves")));
		assertEquals(1, f.copies("Dizana's quiver (uncharged)"));
	}

	@Test
	public void aMaledictusWhoseTopDamageWasAnotherPlayersIsLeftOut()
	{
		loot(LootRecordType.NPC, "Revenant maledictus", null, ETHER, 12, COINS, 5000);
		loot(LootRecordType.NPC, "Revenant maledictus", null, BLIGHTED_RESTORE, 1, BLIGHTED_MANTA, 2);
		BossState r = boss("revenant-maledictus");
		assertEquals(2, r.kc);
		assertEquals(100, num(param("revenant-maledictus", 1, "looted")));
		assertEquals(0, num(param("revenant-maledictus", 2, "looted")));
	}

	@Test
	public void theKindOfWorldIsRead()
	{
		game.members = false;
		say("Your Brutus kill count is: <col=ff0000>3</col>.");
		loot(LootRecordType.NPC, "Brutus", null, MOOLETA, 1);
		assertEquals(0, num(param("brutus", 3, "members")));
		assertEquals(1, boss("brutus").copies("Mooleta"));
	}

	@Test
	public void everySettingTheGameRevealsIsOneTheBossReads()
	{
		// what the plugin reads must be listed as read (READS), or the player's value for older kills would overwrite it
		game.varbits.put(VarbitID.BARROWS_KILLED_AHRIM, 1);
		game.varbits.put(VarbitID.PMOON_BOSS_BLUE_DEAD, 1);
		game.varbits.put(VarbitID.TOB_CLIENT_P0, 1);
		game.varps.put(VarPlayerID.RAIDS_PLAYERSCORE, 25_000);
		say("Wave 7 completed! Wave duration: 3:15.00");
		java.util.List<Capture.Stack> stacks = java.util.Arrays.asList(new Capture.Stack("Big bones", 1), new Capture.Stack("Raw rat meat", 1));
		StringBuilder bad = new StringBuilder();
		java.util.Set<String> all = new java.util.TreeSet<>();
		for (Data.Boss b : tracker.core().data.bosses)
		{
			java.util.Set<String> keys = new java.util.TreeSet<>();
			keys.addAll(tracker.hints(b.id, stacks, null).keySet());
			keys.addAll(tracker.hints(b.id, stacks, 5).keySet());
			keys.addAll(tracker.hints(b.id, stacks, new int[] {300, 3, 12_000}).keySet());
			for (BossIndex.KcLabel kl : tracker.core().index.labels(b.id))
			{
				keys.addAll(kl.patch.keySet());
			}
			keys.addAll(b.onLoot.keySet());
			keys.addAll(b.noLoot.keySet());
			java.util.Set<String> params = new java.util.HashSet<>();
			for (Data.Param p : b.params)
			{
				params.add(p.name);
			}
			keys.retainAll(params); // the capture ignores the rest
			all.addAll(keys);
			keys.removeAll(b.reads);
			if (!keys.isEmpty())
			{
				bad.append(b.id).append(": ").append(keys).append('\n');
			}
		}
		assertEquals("", bad.toString());
		assertEquals("[awakened, blood, blue, brothers, choice, cm, delve, destroy, eclipse, farming, fishing, looted, members, mode, mvp, points, raidLevel, "
			+ "team, waves]", all.toString());
	}

	@Test
	public void aKillWithoutLootIsOnlyLeftOutWhileTheLootTrackerIsOn()
	{
		say("Your General Graardor kill count is: <col=ff0000>100</col>.");
		loot(LootRecordType.NPC, "General Graardor", null, BANDOS_TASSETS, 1);
		say("Your General Graardor kill count is: <col=ff0000>101</col>.");
		ticks(60);
		assertEquals(100, num(param("general-graardor", 100, "looted")));
		assertEquals(0, num(param("general-graardor", 101, "looted")));
		game.lootTracker = false;
		ticks(60);
		say("Your General Graardor kill count is: <col=ff0000>102</col>.");
		ticks(60);
		assertEquals(100, num(param("general-graardor", 102, "looted")));
	}
}
