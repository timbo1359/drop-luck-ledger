package com.dropluckledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.dropluckledger.capture.KcMessages;
import com.dropluckledger.history.HistoryPlanner;
import com.dropluckledger.ledger.BossState;
import com.dropluckledger.ledger.Drop;
import com.dropluckledger.ledger.Ledger;
import com.dropluckledger.plugin.FakeGame;
import com.dropluckledger.plugin.FakeNode;
import com.dropluckledger.plugin.TrackingState;
import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.ScriptID;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.util.Filepath;
import net.runelite.http.api.loottracker.LootRecordType;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Drives the tracking the way the plugin does (start, game events, stop) with a fake game.
 */
public class TrackerTest
{
	private static final int NECKLACE = 22111;
	private static final int VISAGE = 11286;
	private static final int FANG = 12922;
	private static final int COINS = 995;

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private Tracker tracker;
	private FakeGame game;
	private ScheduledExecutorService executor;
	private DropLuckLedgerConfig config = config(true, true);
	private File data;

	@Before
	public void setUp() throws Exception
	{
		data = tmp.newFolder("plugin-data");
		game = new FakeGame().addItem(NECKLACE, "Dragonbone necklace").addItem(VISAGE, "Skeletal visage").addItem(FANG, "Tanzanite fang").addItem(COINS, "Coins")
			.addItem(30001, "Osmumten's fang").addItem(30002, "Tiny tempor").addItem(30003, "Tangleroot");
		executor = Executors.newSingleThreadScheduledExecutor();
		tracker = newTracker();
	}

	@After
	public void tearDown() throws Exception
	{
		tracker.stop();
		executor.shutdownNow();
	}

	private static DropLuckLedgerConfig config(boolean chatMessages, boolean importOnLogin)
	{
		return new DropLuckLedgerConfig()
		{
			@Override
			public boolean chatMessages()
			{
				return chatMessages;
			}

			@Override
			public boolean importOnLogin()
			{
				return importOnLogin;
			}
		};
	}

	/**
	 * What the plugin class does on start-up: a new tracker on the plugin's own data folder.
	 */
	private Tracker newTracker() throws IOException
	{
		Filepath dir = TestFiles.root(data.toPath()).joinSegment("drop-luck-ledger").rooted();
		return new Tracker(game, config, dir, new Gson(), executor);
	}

	// ------------------------------------------------------------------ helpers

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

	private void start() throws Exception
	{
		tracker.start();
		waitFor("the boss data", () -> tracker.core() != null);
	}

	private void login(long hash) throws Exception
	{
		game.logIn(hash);
		tracker.onGameStateChanged(GameState.LOGGED_IN);
		waitFor("tracking", () -> tracker.state() == TrackingState.TRACKING);
	}

	private void logout() throws Exception
	{
		game.logOut();
		tracker.onGameStateChanged(GameState.LOGIN_SCREEN);
		waitFor("logged out", () -> tracker.state() == TrackingState.LOGGED_OUT);
	}

	/**
	 * A line of game chat.
	 */
	private void say(String text)
	{
		game.tick++;
		tracker.onChatMessage(ChatMessageType.GAMEMESSAGE, text);
	}

	private void loot(String name, Object metadata, int... idsAndQuantities)
	{
		int[] ids = new int[idsAndQuantities.length / 2];
		int[] quantities = new int[idsAndQuantities.length / 2];
		for (int i = 0; i < ids.length; i++)
		{
			ids[i] = idsAndQuantities[i * 2];
			quantities[i] = idsAndQuantities[i * 2 + 1];
		}
		game.tick++;
		tracker.onLoot(LootRecordType.NPC, name, 1, ids, quantities, metadata);
	}

	private BossState boss(String id)
	{
		return tracker.core().ledger.copyOf(id);
	}

	private static String vorkathKc(int n)
	{
		return "Your Vorkath kill count is: <col=ff0000>" + n + "</col>.";
	}

	private File ledgerFile(long hash)
	{
		return new File(data, "drop-luck-ledger/ledger-" + hash + ".json");
	}

	private String text(File f) throws IOException
	{
		return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
	}

	// ------------------------------------------------------------------ lifecycle

	@Test
	public void startsWithAPanelAndStops() throws Exception
	{
		start();
		assertNotNull(tracker.panel());
		assertEquals(TrackingState.LOGGED_OUT, tracker.state());
		tracker.stop();
		drain();
		assertNull(tracker.panel());
		assertNull(tracker.core());
	}

	@Test
	public void startingWhileAlreadyLoggedInPicksTheAccountUp() throws Exception
	{
		game.logIn(555);
		tracker.start();
		waitFor("tracking", () -> tracker.state() == TrackingState.TRACKING);
		say(vorkathKc(10));
		assertEquals(10, boss("vorkath").kc);
	}

	@Test
	public void switchingTheOffAndOnAgainKeepsTheData() throws Exception
	{
		start();
		login(7);
		say(vorkathKc(25));
		tracker.stop();
		drain();
		assertNull(tracker.core());
		tracker = newTracker();
		start();
		login(7);
		assertEquals(25, boss("vorkath").kc);
	}

	@Test
	public void turnedOffRightAfterStartingLeavesNothingRunning() throws Exception
	{
		tracker.start();
		tracker.stop();
		drain(); // the load was queued first, so it has finished (or given up) by now
		assertNull(tracker.core());
		assertEquals(TrackingState.STARTING, tracker.state());
	}

	// ------------------------------------------------------------------ recording

	@Test
	public void recordsAKillAndItsDrop() throws Exception
	{
		start();
		login(111);
		say(vorkathKc(1234));
		loot("Vorkath", null, COINS, 40000, NECKLACE, 1);
		BossState v = boss("vorkath");
		assertEquals(1234, v.kc);
		assertEquals(1, v.drops.size());
		assertEquals("Dragonbone necklace", v.drops.get(0).item);
		assertEquals(Integer.valueOf(1234), v.drops.get(0).kc);
		assertEquals(Drop.SRC_LIVE, v.drops.get(0).src);
		// the game message
		assertEquals(1, game.chat.size());
		String m = KcMessages.stripTags(game.chat.get(0));
		assertTrue(m, m.startsWith("Logged Dragonbone necklace at Vorkath kill 1,234"));
	}

	@Test
	public void lootBeforeTheKillCountMessageGivesTheSameResult() throws Exception
	{
		start();
		login(111);
		say(vorkathKc(99));
		loot("Vorkath", null, COINS, 1);
		loot("Vorkath", null, VISAGE, 1);
		say(vorkathKc(100)); // the next kill's message comes after its loot
		BossState v = boss("vorkath");
		assertEquals(100, v.kc);
		assertEquals(Integer.valueOf(100), v.drops.get(0).kc);
	}

	@Test
	public void chatMessagesCanBeSwitchedOff() throws Exception
	{
		config = config(false, true);
		tracker = newTracker();
		start();
		login(111);
		say(vorkathKc(5));
		loot("Vorkath", null, NECKLACE, 1);
		assertEquals(1, boss("vorkath").drops.size());
		assertTrue(game.chat.isEmpty());
	}

	@Test
	public void ignoresEverythingBeforeLogin() throws Exception
	{
		start();
		say(vorkathKc(10));
		loot("Vorkath", null, NECKLACE, 1);
		assertEquals(0, boss("vorkath").kc);
		login(111);
		assertEquals(0, boss("vorkath").kc);
	}

	@Test
	public void onlyGameChatCountsAsKillCountMessages() throws Exception
	{
		start();
		login(111);
		game.tick++;
		tracker.onChatMessage(ChatMessageType.PUBLICCHAT, vorkathKc(50)); // a player typing it
		assertEquals(0, boss("vorkath").kc);
		game.tick++;
		tracker.onChatMessage(ChatMessageType.SPAM, vorkathKc(51));
		assertEquals(51, boss("vorkath").kc);
	}

	@Test
	public void lootFromThingsThatAreNotBossesIsIgnored() throws Exception
	{
		start();
		login(111);
		loot("Goblin", null, COINS, 5);
		assertTrue(tracker.core().ledger.activeBosses().isEmpty());
	}

	@Test
	public void lootFromPlayersIsIgnored() throws Exception
	{
		start();
		login(111);
		tracker.onLoot(LootRecordType.PLAYER, "Vorkath", 1, new int[]{NECKLACE}, new int[]{1}, null);
		assertTrue(tracker.core().ledger.activeBosses().isEmpty());
	}

	@Test
	public void theBossesThePluginLeavesOutAreIgnored() throws Exception
	{
		start();
		login(111);
		say("Your completed Chambers of Xeric count is: <col=ff0000>51</col>.");
		say("Your completed Theatre of Blood count is: <col=ff0000>12</col>.");
		say("Your Tombs of Amascut: Expert Mode kill count is: <col=ff0000>7</col>.");
		say("Your Royal Titans kill count is: <col=ff0000>10</col>.");
		say("Your subdued Wintertodt count is: <col=ff0000>300</col>.");
		loot("Tombs of Amascut", new int[]{425, 3, 12000}, COINS, 1);
		loot("Branda the Fire Queen", null, COINS, 1);
		loot("Reward cart (Wintertodt)", 99, COINS, 1);
		assertTrue(tracker.core().ledger.activeBosses().isEmpty());
	}

	@Test
	public void skillLevelsAreTakenFromTheGame() throws Exception
	{
		game.levels.put(Skill.FISHING, 77);
		game.levels.put(Skill.FARMING, 64);
		start();
		login(111);
		loot("Reward pool (Tempoross)", 81, 30002, 1);
		assertEquals(77, ((Number) boss("tempoross").lastPeriod().params.get("fishing")).intValue());
		loot("Hespori", null, 30003, 1);
		assertEquals(64, ((Number) boss("hespori").lastPeriod().params.get("farming")).intValue());
	}

	@Test
	public void delveLevelComesFromTheRewardMetadata() throws Exception
	{
		start();
		login(111);
		loot("Doom of Mokhaiotl", 14, COINS, 1);
		assertEquals(14, ((Number) boss("doom-of-mokhaiotl").lastPeriod().params.get("delve")).intValue());
	}

	// ------------------------------------------------------------------ accounts and worlds

	@Test
	public void eachAccountHasItsOwnLedger() throws Exception
	{
		start();
		login(111);
		say(vorkathKc(50));
		loot("Vorkath", null, NECKLACE, 1);
		logout();
		assertEquals(0, boss("vorkath").kc);
		assertTrue(ledgerFile(111).isFile());
		login(222);
		assertEquals(0, boss("vorkath").kc);
		say(vorkathKc(8));
		logout();
		login(111);
		BossState v = boss("vorkath");
		assertEquals(50, v.kc);
		assertEquals(1, v.drops.size());
		logout();
		login(222);
		assertEquals(8, boss("vorkath").kc);
	}

	@Test
	public void loggingOutWritesTheFileAtOnce() throws Exception
	{
		start();
		login(111);
		say(vorkathKc(50));
		loot("Vorkath", null, NECKLACE, 1);
		logout();
		String json = text(ledgerFile(111));
		assertTrue(json, json.contains("\"drop-luck-ledger\""));
		assertTrue(json, json.contains("Dragonbone necklace"));
		assertTrue(json, json.contains("\"kc\":50"));
	}

	@Test
	public void worldsWithTheirOwnSaveArePaused() throws Exception
	{
		start();
		login(111);
		say(vorkathKc(50));
		game.standardWorld = false;
		tracker.onGameStateChanged(GameState.LOGGED_IN);
		waitFor("paused", () -> tracker.state() == TrackingState.PAUSED);
		say(vorkathKc(900));
		loot("Vorkath", null, NECKLACE, 1);
		assertEquals(0, boss("vorkath").kc);
		// back on a normal world: the account's ledger is back, and nothing from the paused time is in it
		game.standardWorld = true;
		tracker.onGameStateChanged(GameState.LOGGED_IN);
		waitFor("tracking", () -> tracker.state() == TrackingState.TRACKING);
		assertEquals(50, boss("vorkath").kc);
		assertTrue(boss("vorkath").drops.isEmpty());
	}

	@Test
	public void hoppingWorldsOnTheSameAccountDoesNotReload() throws Exception
	{
		start();
		login(111);
		say(vorkathKc(50));
		Tracker.Core before = tracker.core();
		tracker.onGameStateChanged(GameState.HOPPING);
		tracker.onGameStateChanged(GameState.LOGGED_IN);
		drain();
		assertEquals(TrackingState.TRACKING, tracker.state());
		assertEquals(50, boss("vorkath").kc);
		assertSame(before, tracker.core());
	}

	@Test
	public void anUnreadableLedgerFileIsSetAsideNotOverwritten() throws Exception
	{
		File dir = new File(data, "drop-luck-ledger");
		assertTrue(dir.mkdirs());
		Files.write(new File(dir, "ledger-111.json").toPath(), "{ this is not a ledger".getBytes(StandardCharsets.UTF_8));
		start();
		login(111);
		assertEquals(0, boss("vorkath").kc);
		boolean setAside = false;
		for (File f : dir.listFiles())
		{
			if (f.getName().startsWith("ledger-111.json.unreadable-"))
			{
				setAside = true;
				assertEquals("{ this is not a ledger", text(f));
			}
		}
		assertTrue(setAside);
		say(vorkathKc(3));
		tracker.core().saver.flushNow();
		assertTrue(text(ledgerFile(111)).contains("\"kc\":3"));
	}

	@Test
	public void shuttingDownWritesPendingChanges() throws Exception
	{
		start();
		login(111);
		tracker.core().ledger.setKc("zulrah", 77); // would be saved 1.5 seconds from now
		tracker.stop();
		drain();
		assertTrue(text(ledgerFile(111)).contains("\"kc\":77"));
	}

	@Test
	public void closingRuneLiteWaitsForTheWrite() throws Exception
	{
		start();
		login(111);
		tracker.core().ledger.setKc("zulrah", 78);
		Future<?> write = tracker.flushForShutdown();
		assertNotNull(write);
		write.get(10, TimeUnit.SECONDS);
		assertTrue(text(ledgerFile(111)).contains("\"kc\":78"));
	}

	@Test
	public void closingRuneLiteBeforeAnythingLoadedHasNothingToWaitFor() throws Exception
	{
		assertNull(tracker.flushForShutdown());
	}

	// ------------------------------------------------------------------ the game's own records

	private void openKillLog(String title, String[] names, String[] counts)
	{
		game.widgets.put(InterfaceID.KillLog.INTERFACE_TITLE, FakeNode.text(title));
		FakeNode[] n = new FakeNode[names.length];
		FakeNode[] k = new FakeNode[names.length];
		for (int i = 0; i < names.length; i++)
		{
			n[i] = FakeNode.text(names[i]);
			k[i] = FakeNode.text(counts[i]);
		}
		game.widgets.put(InterfaceID.KillLog.NAME, FakeNode.withChildren(n));
		game.widgets.put(InterfaceID.KillLog.KILL, FakeNode.withChildren(k));
		tracker.onWidgetLoaded(InterfaceID.KILL_LOG);
	}

	@Test
	public void theBossKillLogIsReadWhenItOpens() throws Exception
	{
		start();
		login(111);
		openKillLog("Boss Kill Log", new String[]{"Zulrah", "Vorkath"}, new String[]{"1,500", "320"});
		assertTrue(tracker.core().ledger.history().kc.isEmpty()); // read on the next tick, when the rows are filled in
		tracker.onGameTick();
		assertEquals(1500, (int) tracker.core().ledger.history().kc.get("Zulrah"));
		assertEquals(320, (int) tracker.core().ledger.history().kc.get("Vorkath"));
		// it is only staged: the ledger's own counts do not change until the player imports
		assertEquals(0, boss("zulrah").kc);
	}

	@Test
	public void anotherPlayersKillLogIsNeverRead() throws Exception
	{
		start();
		login(111);
		game.widgets.put(InterfaceID.MenuNew.TITLE, FakeNode.withChildren(FakeNode.text("The Exploits of Someone Else")));
		tracker.onWidgetLoaded(InterfaceID.MENU_NEW);
		openKillLog("Boss Kill Log", new String[]{"Zulrah"}, new String[]{"99,999"});
		tracker.onGameTick();
		assertTrue(tracker.core().ledger.history().kc.isEmpty());
		// the same log in the player's own adventure log is read
		game.widgets.put(InterfaceID.MenuNew.TITLE, FakeNode.withChildren(FakeNode.text("The Exploits of Tester")));
		tracker.onWidgetLoaded(InterfaceID.MENU_NEW);
		openKillLog("Boss Kill Log", new String[]{"Zulrah"}, new String[]{"123"});
		tracker.onGameTick();
		assertEquals(123, (int) tracker.core().ledger.history().kc.get("Zulrah"));
	}

	@Test
	public void collectionLogPagesAreReadAndTeachTheItemPictures() throws Exception
	{
		start();
		login(111);
		game.widgets.put(InterfaceID.Collection.HEADER_TEXT, FakeNode.withChildren(FakeNode.text("Zulrah")));
		game.widgets.put(InterfaceID.Collection.ITEMS_CONTENTS, FakeNode.withChildren(FakeNode.item(FANG, 2, true), FakeNode.item(11286, 0, false)));
		tracker.onScriptPostFired(ScriptID.COLLECTION_DRAW_LIST);
		assertEquals(2, (int) tracker.core().ledger.history().log.get("Zulrah").get("Tanzanite fang"));
		assertEquals(FANG, tracker.core().icons.id("Tanzanite fang"));
		assertEquals(VISAGE, tracker.core().icons.id("Skeletal visage")); // not owned, but on the page
		// other scripts are ignored
		tracker.onScriptPostFired(1);
		assertEquals(1, tracker.core().ledger.history().log.size());
	}

	@Test
	public void historyIsOnlyReadWhileTracking() throws Exception
	{
		start();
		openKillLog("Boss Kill Log", new String[]{"Zulrah"}, new String[]{"5"});
		tracker.onGameTick();
		assertTrue(tracker.core().ledger.history().kc.isEmpty());
	}

	// ------------------------------------------------------------------ offering history in the panel

	@Test
	public void historyFromRuneLiteIsOfferedOnTheFirstLoginOfAnEmptyLedger() throws Exception
	{
		game.save("killcount", "zulrah", "1500");
		start();
		login(111);
		waitFor("the offer", () -> tracker.panel().offeredPlan() != null);
		HistoryPlanner.Plan plan = tracker.panel().offeredPlan();
		assertEquals(1, plan.kcToApply());
		assertEquals("zulrah", plan.kc.get(0).bossId);
		assertEquals(1500, plan.kc.get(0).to);
		// only offered: nothing is in the ledger until the player looks at it and agrees
		assertEquals(0, boss("zulrah").kc);
		assertEquals(1, tracker.panel().noteTexts().size());
		assertTrue(tracker.panel().noteTexts().get(0), tracker.panel().noteTexts().get(0).contains("1 kill count"));
	}

	@Test
	public void nothingIsOfferedWhenThereIsNothingToBringIn() throws Exception
	{
		start();
		login(111);
		drain();
		assertNull(tracker.panel().offeredPlan());
		assertTrue(tracker.panel().noteTexts().isEmpty());
	}

	@Test
	public void nothingIsOfferedWhenTheSettingIsOff() throws Exception
	{
		config = config(true, false);
		tracker = newTracker();
		game.save("killcount", "zulrah", "1500");
		start();
		login(111);
		drain();
		assertNull(tracker.panel().offeredPlan());
	}

	@Test
	public void nothingIsOfferedToALedgerThatAlreadyHasKills() throws Exception
	{
		start();
		login(111);
		say(vorkathKc(10));
		logout();
		game.save("killcount", "zulrah", "1500");
		login(111);
		drain();
		assertNull(tracker.panel().offeredPlan());
	}

	@Test
	public void eachNewAccountIsOfferedItsOwnHistory() throws Exception
	{
		game.save("killcount", "zulrah", "1500");
		start();
		login(111);
		waitFor("the first offer", () -> tracker.panel().offeredPlan() != null);
		logout();
		assertNull("the offer goes away with the account", tracker.panel().offeredPlan());
		login(222);
		waitFor("the second offer", () -> tracker.panel().offeredPlan() != null);
		assertEquals(1500, tracker.panel().offeredPlan().kc.get(0).to);
	}

	@Test
	public void readingTheKillLogTellsThePlayer() throws Exception
	{
		start();
		login(111);
		openKillLog("Boss Kill Log", new String[]{"Zulrah", "Vorkath"}, new String[]{"1,500", "320"});
		tracker.onGameTick();
		waitFor("the note", () -> tracker.panel().noteTexts().contains("Boss Kill Log: 2 bosses read"));
		assertEquals(0, boss("zulrah").kc);
	}

	@Test
	public void readingACollectionLogPageTellsThePlayer() throws Exception
	{
		start();
		login(111);
		game.widgets.put(InterfaceID.Collection.HEADER_TEXT, FakeNode.withChildren(FakeNode.text("Zulrah")));
		game.widgets.put(InterfaceID.Collection.ITEMS_CONTENTS, FakeNode.withChildren(FakeNode.item(FANG, 2, true)));
		tracker.onScriptPostFired(ScriptID.COLLECTION_DRAW_LIST);
		waitFor("the note", () -> tracker.panel().noteTexts().contains("Collection Log page read: Zulrah"));
	}

	@Test
	public void thePanelFollowsTheGame() throws Exception
	{
		start();
		login(111);
		say(vorkathKc(1234));
		loot("Vorkath", null, COINS, 40000, NECKLACE, 1);
		waitFor("the overview", () -> "overview".equals(tracker.panel().viewName()));
		assertEquals("", tracker.panel().stateText());
		logout();
		assertEquals("none", tracker.panel().viewName());
		assertEquals(TrackingState.LOGGED_OUT.message(), tracker.panel().stateText());
	}

	// ------------------------------------------------------------------ backups

	@Test
	public void exportThenImportMovesTheLedger() throws Exception
	{
		start();
		login(111);
		say(vorkathKc(60));
		loot("Vorkath", null, VISAGE, 1);
		File backup = new File(tmp.getRoot(), "mine.json");
		game.fileToSave = TestFiles.root(backup.toPath());
		SwingUtilities.invokeAndWait(() -> tracker.host().exportBackup(null));
		drain();
		assertTrue(text(backup).contains("Skeletal visage"));

		logout();
		login(222);
		assertEquals(0, boss("vorkath").kc);
		game.fileToOpen = TestFiles.root(backup.toPath());
		final List<Ledger> asked = new ArrayList<>();
		SwingUtilities.invokeAndWait(() -> tracker.host().importBackup(null, asked::add));
		drain();
		assertEquals(1, asked.size());
		tracker.host().replaceLedger(asked.get(0));
		drain();
		assertEquals(60, boss("vorkath").kc);
		assertEquals(1, boss("vorkath").copies("Skeletal visage"));
	}

	@Test
	public void importingSomethingThatIsNotABackupChangesNothing() throws Exception
	{
		start();
		login(111);
		say(vorkathKc(60));
		File junk = new File(tmp.getRoot(), "junk.json");
		Files.write(junk.toPath(), "hello".getBytes(StandardCharsets.UTF_8));
		game.fileToOpen = TestFiles.root(junk.toPath());
		final List<Ledger> asked = new ArrayList<>();
		SwingUtilities.invokeAndWait(() -> tracker.host().importBackup(null, asked::add));
		drain();
		assertTrue(asked.isEmpty());
		assertEquals(60, boss("vorkath").kc);
		waitFor("the warning", () -> tracker.panel().noteTexts().stream().anyMatch(t -> t.startsWith("That file is not a Drop Luck Ledger backup")));
	}

	@Test
	public void cancellingTheFileChooserDoesNothing() throws Exception
	{
		start();
		login(111);
		game.fileToOpen = null;
		game.fileToSave = null;
		final List<Ledger> asked = new ArrayList<>();
		SwingUtilities.invokeAndWait(() -> tracker.host().importBackup(null, asked::add));
		SwingUtilities.invokeAndWait(() -> tracker.host().exportBackup(null));
		drain();
		assertTrue(asked.isEmpty());
		assertFalse(new File(tmp.getRoot(), "mine.json").exists());
		assertTrue(tracker.panel().noteTexts().isEmpty());
	}

	// ------------------------------------------------------------------ item pictures

	@Test
	public void itemsSeenInLootAreRememberedBetweenSessions() throws Exception
	{
		start();
		login(111);
		say(vorkathKc(5));
		loot("Vorkath", null, NECKLACE, 1);
		assertEquals(NECKLACE, tracker.core().icons.id("Dragonbone necklace"));
		tracker.stop();
		drain();
		File saved = new File(data, "drop-luck-ledger/item-ids.json");
		assertTrue(saved.isFile());
		assertTrue(text(saved).contains("dragonbonenecklace"));
		tracker = newTracker();
		start();
		assertEquals(NECKLACE, tracker.core().icons.id("Dragonbone necklace"));
	}
}
