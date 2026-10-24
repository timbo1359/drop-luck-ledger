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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import net.runelite.api.gameval.InventoryID;
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
		say("Your Royal Titans kill count is: <col=ff0000>10</col>.");
		say("Your subdued Wintertodt count is: <col=ff0000>300</col>.");
		loot("Branda the Fire Queen", null, COINS, 1);
		loot("Reward cart (Wintertodt)", 99, COINS, 1);
		assertTrue(tracker.core().ledger.activeBosses().isEmpty());
	}

	@Test
	public void theRaidsAreRecorded() throws Exception
	{
		start();
		login(111);
		say("Your completed Chambers of Xeric count is: <col=ff0000>51</col>.");
		say("Your completed Theatre of Blood count is: <col=ff0000>12</col>.");
		say("Your completed Tombs of Amascut: Expert Mode count is: <col=ff0000>7</col>.");
		say("Your completed The Fractured Archive count is: <col=ff0000>2</col>.");
		assertEquals(51, boss("chambers-of-xeric").kc);
		assertEquals(12, boss("theatre-of-blood").kc);
		assertEquals(7, boss("tombs-of-amascut").kc);
		assertEquals(2, boss("the-fractured-archive").kc);
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

	/**
	 * The game draws a collection log page: its header lines (title first) and its items, from the inventory the game holds at that
	 * moment.
	 */
	private void drawPage(FakeNode[] items, String... header)
	{
		FakeNode[] lines = new FakeNode[header.length];
		for (int i = 0; i < header.length; i++)
		{
			lines[i] = FakeNode.text(header[i]);
		}
		game.widgets.put(InterfaceID.Collection.HEADER_TEXT, FakeNode.withChildren(lines));
		game.widgets.put(InterfaceID.Collection.ITEMS_CONTENTS, FakeNode.withChildren(items));
		tracker.onScriptPostFired(ScriptID.COLLECTION_DRAW_LIST);
	}

	/**
	 * What the server has sent for the counts under a page's title: the three player variables every page draws them from.
	 */
	private void pageCounts(int... values)
	{
		int[] varps = {VarPlayerID.COLLECTION_CATEGORY_COUNT, VarPlayerID.COLLECTION_CATEGORY_COUNT2, VarPlayerID.COLLECTION_CATEGORY_COUNT3};
		for (int i = 0; i < varps.length; i++)
		{
			game.varps.put(varps[i], i < values.length ? values[i] : 0);
		}
	}

	/**
	 * What the server has sent for the page on screen: the copies of its items.
	 */
	private void pageItems(int... idsAndCopies)
	{
		Map<Integer, Integer> inv = new HashMap<>();
		for (int i = 0; i < idsAndCopies.length; i += 2)
		{
			inv.put(idsAndCopies[i], idsAndCopies[i + 1]);
		}
		game.inventories.put(InventoryID.COLLECTION_TRANSMIT, inv);
	}

	/**
	 * Game ticks go by until a page drawn now has settled.
	 */
	private void settle() throws Exception
	{
		for (int i = 0; i < Tracker.SETTLE_TICKS; i++)
		{
			game.tick++;
			tracker.onGameTick();
		}
		drain();
	}

	private HistoryPlanner.Plan offered() throws Exception
	{
		waitFor("the list from the game's records", () -> tracker.panel().syncPlan() != null);
		return tracker.panel().syncPlan();
	}

	/**
	 * The player presses Add on the list the panel offers.
	 */
	private int[] add() throws Exception
	{
		int[] done = tracker.host().applySync(offered());
		drain();
		return done;
	}

	@Test
	public void theBossKillLogIsReadWhenItOpensAndNothingChangesUntilThePlayerAgrees() throws Exception
	{
		start();
		login(111);
		openKillLog("Boss Kill Log", new String[]{"Zulrah", "Vorkath", "Kraken"}, new String[]{"1,500", "320", "0"});
		drain();
		assertNull(tracker.panel().syncPlan()); // read on the next tick, when the rows are filled in
		tracker.onGameTick();
		// listed for the player, not added
		HistoryPlanner.Plan plan = offered();
		assertEquals(2, plan.kcToApply());
		assertEquals(0, boss("zulrah").kc);
		assertTrue(tracker.panel().noteTexts().toString(), tracker.panel().noteTexts().contains(
			"Boss Kill Log: kill counts Vorkath 0 → 320, Zulrah 0 → 1,500. Nothing changes until you add them."));
		// the player agrees: the kills it was missing count, with no drops on them
		assertEquals(2, add()[0]);
		assertEquals(1500, boss("zulrah").kc);
		assertEquals(320, boss("vorkath").kc);
		assertTrue(boss("zulrah").drops.isEmpty());
		// read again with nothing new, it only says it was read
		openKillLog("Boss Kill Log", new String[]{"Zulrah", "Vorkath"}, new String[]{"1,500", "320"});
		tracker.onGameTick();
		waitFor("the note", () -> tracker.panel().noteTexts().contains("Boss Kill Log read. Your ledger matches it."));
	}

	@Test
	public void aCountThatWentTooHighIsOfferedBackDownToTheGames() throws Exception
	{
		start();
		login(111);
		// what an earlier version did with a collection log page drawn with the previous page's numbers
		tracker.core().capture.onShownCount("Thermonuclear Smoke Devil kills", 5);
		openKillLog("Boss Kill Log", new String[]{"Thermonuclear smoke devil"}, new String[]{"0"});
		tracker.onGameTick();
		assertTrue(offered().kc.get(0).lower);
		assertEquals(5, boss("thermonuclear-smoke-devil").kc);
		add();
		assertEquals(0, boss("thermonuclear-smoke-devil").kc);
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
		drain();
		assertNull(tracker.panel().syncPlan());
		assertTrue(tracker.panel().noteTexts().toString(), tracker.panel().noteTexts().isEmpty());
		// the same log in the player's own adventure log is read
		game.widgets.put(InterfaceID.MenuNew.TITLE, FakeNode.withChildren(FakeNode.text("The Exploits of Tester")));
		tracker.onWidgetLoaded(InterfaceID.MENU_NEW);
		openKillLog("Boss Kill Log", new String[]{"Zulrah"}, new String[]{"123"});
		tracker.onGameTick();
		add();
		assertEquals(123, boss("zulrah").kc);
	}

	@Test
	public void aCollectionLogPageIsReadOnceItHasSettled() throws Exception
	{
		start();
		login(111);
		pageItems(FANG, 2);
		drawPage(new FakeNode[]{FakeNode.item(FANG, 2, true), FakeNode.item(VISAGE, 0, false)}, "Zulrah", "Obtained: 1/8", "Zulrah kills: 0");
		// the pictures are learnt at once, owned or not
		assertEquals(FANG, tracker.core().icons.id("Tanzanite fang"));
		assertEquals(VISAGE, tracker.core().icons.id("Skeletal visage"));
		game.tick++;
		tracker.onGameTick();
		drain();
		assertNull(tracker.panel().syncPlan());
		game.tick++;
		tracker.onGameTick();
		HistoryPlanner.Plan plan = offered();
		assertEquals(2, plan.copiesToAdd());
		// its count, 0, is the ledger's too
		assertTrue(plan.kc.isEmpty());
		// other scripts are ignored
		tracker.onScriptPostFired(1);
		settle();
		assertSame(plan, tracker.panel().syncPlan());
	}

	@Test
	public void clickingQuicklyThroughThePagesChecksOnlyThePageStoppedAt() throws Exception
	{
		start();
		login(111);
		// a tick apart, each page drawn with the previous page's count under its title, its own count coming just after
		pageItems();
		pageCounts(81);
		drawPage(new FakeNode[]{FakeNode.item(FANG, 0, false)}, "Zulrah", "Zulrah kills: 81");
		pageCounts(5);
		game.tick++;
		tracker.onGameTick();
		drawPage(new FakeNode[]{FakeNode.item(NECKLACE, 0, false)}, "Vorkath", "Vorkath kills: 5");
		pageCounts(7);
		game.tick++;
		tracker.onGameTick();
		pageItems(VISAGE, 1);
		drawPage(new FakeNode[]{FakeNode.item(VISAGE, 1, true)}, "Chaos Elemental", "Chaos Elemental kills: 7");
		pageCounts(50);
		settle();
		// only the page stopped at, with its own count
		HistoryPlanner.Plan plan = offered();
		assertEquals(Collections.singleton("Collection Log (Chaos Elemental)"), plan.sources);
		assertEquals(1, plan.kc.size());
		assertEquals("chaos-elemental", plan.kc.get(0).bossId);
		assertEquals(50, plan.kc.get(0).to);
		assertEquals(0, boss("chaos-elemental").kc);
		assertEquals(0, boss("zulrah").kc);
		assertEquals(0, boss("vorkath").kc);
	}

	@Test
	public void aPageShowingFewerKillsThanTheLedgerOffersToBringItDown() throws Exception
	{
		start();
		login(111);
		// what an earlier version did with a page drawn with the previous page's numbers
		tracker.core().capture.onShownCount("Araxxor kills", 81);
		pageItems();
		pageCounts(0);
		drawPage(new FakeNode[0], "Araxxor", "Obtained: 0/10", "Personal Best: N/A", "Araxxor kills: 0");
		settle();
		waitFor("the note", () -> tracker.panel().noteTexts().contains(
			"Collection Log (Araxxor): kill count Araxxor 81 → 0. Nothing changes until you add it."));
		assertTrue(offered().kc.get(0).lower);
		assertEquals(81, boss("araxxor").kc);
		assertEquals(1, add()[0]);
		assertEquals(0, boss("araxxor").kc);
	}

	@Test
	public void aPageDrawnWithThePreviousPagesNumbersIsCheckedWithItsOwn() throws Exception
	{
		start();
		login(111);
		// the page before left 117 in the count; the Skotizo page is drawn before its own numbers come
		pageItems();
		pageCounts(117);
		drawPage(new FakeNode[0], "Skotizo", "Obtained: 0/6", "Skotizo kills: 117");
		pageCounts(5);
		settle();
		HistoryPlanner.Plan plan = offered();
		assertEquals(1, plan.kc.size());
		assertEquals(5, plan.kc.get(0).to);
		assertEquals(0, boss("skotizo").kc);
		// or the page is drawn again once they come: the last drawing counts
		tracker.host().dismissSync();
		pageCounts(117);
		drawPage(new FakeNode[0], "Skotizo", "Obtained: 0/6", "Skotizo kills: 117");
		pageCounts(7);
		drawPage(new FakeNode[0], "Skotizo", "Obtained: 0/6", "Skotizo kills: 7");
		settle();
		waitFor("the new list", () -> tracker.panel().syncPlan() != null && tracker.panel().syncPlan() != plan);
		assertEquals(7, tracker.panel().syncPlan().kc.get(0).to);
	}

	@Test
	public void whatOfAPageHasSettledIsChecked() throws Exception
	{
		start();
		login(111);
		say("Your Zulrah kill count is: <col=ff0000>100</col>.");
		game.tick += 200;
		tracker.onGameTick();
		// the count drawn with a 3 that two of the variables held, which then part ways: which one the page shows is not known, so
		// only its items are checked
		pageItems(FANG, 1);
		pageCounts(3, 3, 0);
		drawPage(new FakeNode[]{FakeNode.item(FANG, 1, true)}, "Zulrah", "Obtained: 1/8", "Zulrah kills: 3");
		pageCounts(9, 3, 0);
		settle();
		HistoryPlanner.Plan plan = offered();
		assertTrue(plan.kc.isEmpty());
		assertEquals(1, plan.copiesToAdd());
		add();
		// the items drawn before the server sent them (the fang faded, though the player has one), the count settled: only the count
		pageCounts(100);
		drawPage(new FakeNode[]{FakeNode.item(FANG, 0, false)}, "Zulrah", "Obtained: 0/8", "Zulrah kills: 100");
		settle();
		waitFor("the note", () -> tracker.panel().noteTexts().contains(
			"Collection Log, Zulrah: its items were still loading, so only its kill count was checked. Your ledger matches it."));
		assertSame("no new list", plan, tracker.panel().syncPlan());
		// neither settled: not checked at all
		pageCounts(3, 3, 0);
		drawPage(new FakeNode[]{FakeNode.item(FANG, 0, false)}, "Zulrah", "Obtained: 0/8", "Zulrah kills: 3");
		pageCounts(9, 3, 0);
		settle();
		waitFor("the last note", () -> tracker.panel().noteTexts().contains(
			"Collection Log, Zulrah: it was still loading, so it was not checked. Open it again to check it."));
		assertSame("no new list", plan, tracker.panel().syncPlan());
		assertEquals(100, boss("zulrah").kc);
	}

	@Test
	public void itemsDrawnBeforeTheServerSentThemAreNotChecked() throws Exception
	{
		start();
		login(111);
		// drawn with another page's items: the fang shows faded, though the player has one
		pageItems(FANG, 1);
		drawPage(new FakeNode[]{FakeNode.item(FANG, 0, false)}, "Zulrah", "Obtained: 0/8");
		settle();
		waitFor("the note", () -> tracker.panel().noteTexts().contains(
			"Collection Log, Zulrah: it was still loading, so it was not checked. Open it again to check it."));
		assertNull(tracker.panel().syncPlan());
	}

	@Test
	public void aPageWithAnItemDrawnForThePlayersBodyTypeIsStillChecked() throws Exception
	{
		start();
		login(111);
		// the game counts one item under its own id and draws the version for the player's body type, which has another id
		game.varbits.put(VarbitID.COLLECTION_PLAYER_BODYTYPE, 1);
		pageItems(FANG, 1, 6184, 1);
		drawPage(new FakeNode[]{FakeNode.item(FANG, 1, true), FakeNode.item(6186, 1, true)}, "Zulrah");
		settle();
		assertEquals(1, offered().copiesToAdd());
	}

	@Test
	public void aPageWhoseBossChangedWhileItOpenedIsNotChecked() throws Exception
	{
		start();
		login(111);
		pageItems(FANG, 1);
		drawPage(new FakeNode[]{FakeNode.item(FANG, 1, true)}, "Zulrah");
		// a kill and a fang come in before the page has settled: its count may be from before them
		say("Your Zulrah kill count is: <col=ff0000>100</col>.");
		loot("Zulrah", null, FANG, 1);
		settle();
		waitFor("the note", () -> tracker.panel().noteTexts().contains(
			"Collection Log, Zulrah: your ledger changed while it opened, so it was not checked. Open it again to check it."));
		assertNull(tracker.panel().syncPlan());
		// opened again, it is checked
		drawPage(new FakeNode[]{FakeNode.item(FANG, 1, true)}, "Zulrah");
		settle();
		waitFor("the note", () -> tracker.panel().noteTexts().contains("Collection Log page read: Zulrah. Your ledger matches it."));
	}

	@Test
	public void aKillBetweenTheBossKillLogOpeningAndBeingReadIsKept() throws Exception
	{
		start();
		login(111);
		say("Your Zulrah kill count is: <col=ff0000>100</col>.");
		game.tick += 200;
		tracker.onGameTick();
		openKillLog("Boss Kill Log", new String[]{"Zulrah", "Vorkath"}, new String[]{"100", "7"});
		// the log's counts were sent as it opened; a kill (its loot seen) lands before it is read
		say("Your Zulrah kill count is: <col=ff0000>101</col>.");
		loot("Zulrah", null);
		tracker.onGameTick();
		HistoryPlanner.Plan plan = offered();
		assertEquals(1, plan.kc.size());
		assertEquals("vorkath", plan.kc.get(0).bossId);
		assertEquals(101, boss("zulrah").kc);
	}

	@Test
	public void aPageDrawnBeforeHoppingIsNotChecked() throws Exception
	{
		start();
		login(111);
		pageItems(FANG, 1);
		drawPage(new FakeNode[]{FakeNode.item(FANG, 1, true)}, "Zulrah");
		tracker.onGameStateChanged(GameState.HOPPING);
		tracker.onGameStateChanged(GameState.LOGGED_IN);
		settle();
		assertNull(tracker.panel().syncPlan());
		assertTrue(tracker.panel().noteTexts().toString(), tracker.panel().noteTexts().isEmpty());
	}

	@Test
	public void historyIsOnlyReadWhileTracking() throws Exception
	{
		start();
		openKillLog("Boss Kill Log", new String[]{"Zulrah"}, new String[]{"5"});
		tracker.onGameTick();
		drain();
		assertNull(tracker.panel().syncPlan());
		assertEquals(0, boss("zulrah").kc);
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
	public void readingACollectionLogPageTellsThePlayer() throws Exception
	{
		start();
		login(111);
		pageItems();
		drawPage(new FakeNode[]{FakeNode.item(FANG, 0, false)}, "Zulrah");
		settle();
		waitFor("the note", () -> tracker.panel().noteTexts().contains("Collection Log page read: Zulrah. Your ledger matches it."));
	}

	@Test
	public void aCollectionLogPageCatchesTheLedgerUpWithAnotherComputer() throws Exception
	{
		start();
		login(111);
		say("Your Zulrah kill count is: <col=ff0000>100</col>.");
		game.tick += 200;
		tracker.onGameTick();
		// 40 more kills and a fang on the other computer
		openKillLog("Boss Kill Log", new String[]{"Zulrah"}, new String[]{"140"});
		tracker.onGameTick();
		pageItems(FANG, 1);
		pageCounts(140);
		drawPage(new FakeNode[]{FakeNode.item(FANG, 1, true)}, "Zulrah", "Obtained: <col=ff0000>1/8</col>", "Zulrah kills: <col=ffffff>140</col>");
		settle();
		waitFor("the note", () -> tracker.panel().noteTexts().contains(
			"Boss Kill Log, Collection Log (Zulrah): kill count Zulrah 100 → 140; Tanzanite fang +1. Nothing changes until you add them."));
		assertEquals(100, boss("zulrah").kc);
		add();
		BossState z = boss("zulrah");
		assertEquals(140, z.kc);
		assertEquals(1, z.copies("Tanzanite fang"));
		assertNull(z.drops.get(0).kc);
		// the next kill on this computer follows on from there
		say("Your Zulrah kill count is: <col=ff0000>141</col>.");
		assertEquals(141, boss("zulrah").kc);
	}

	@Test
	public void aCollectionLogOpenedFromTheBookInAnotherPlayersHouseIsNotRead() throws Exception
	{
		start();
		login(111);
		game.widgets.put(InterfaceID.MenuNew.TITLE, FakeNode.withChildren(FakeNode.text("The Exploits of Someone Else")));
		tracker.onWidgetLoaded(InterfaceID.MENU_NEW);
		tracker.onGameTick();
		game.varbits.put(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN, 1);
		pageItems(FANG, 3);
		drawPage(new FakeNode[]{FakeNode.item(FANG, 3, true)}, "Zulrah");
		settle();
		assertNull(tracker.panel().syncPlan());
		// the book in the player's own house is the player's log
		game.widgets.put(InterfaceID.MenuNew.TITLE, FakeNode.withChildren(FakeNode.text("The Exploits of Tester")));
		tracker.onWidgetLoaded(InterfaceID.MENU_NEW);
		tracker.onGameTick();
		drawPage(new FakeNode[]{FakeNode.item(FANG, 3, true)}, "Zulrah");
		settle();
		assertEquals(3, offered().copiesToAdd());
		// a house whose owner is not known: not read
		HistoryPlanner.Plan before = tracker.panel().syncPlan();
		tracker.onGameStateChanged(GameState.LOADING);
		pageItems(FANG, 4);
		drawPage(new FakeNode[]{FakeNode.item(FANG, 4, true)}, "Zulrah");
		settle();
		assertSame(before, tracker.panel().syncPlan());
	}

	@Test
	public void aCollectionLogPageOffersToTakeAwayCopiesTheGameDoesNotCount() throws Exception
	{
		start();
		login(111);
		say("Your Zulrah kill count is: <col=ff0000>100</col>.");
		loot("Zulrah", null, FANG, 1);
		game.tick += 200;
		tracker.onGameTick();
		// a second fang typed in by hand, which the game never gave
		tracker.core().ledger.logDrop("zulrah", "Tanzanite fang", 60, 1, null, Drop.SRC_MANUAL);
		assertEquals(2, boss("zulrah").copies("Tanzanite fang"));
		pageItems(FANG, 1);
		pageCounts(100);
		drawPage(new FakeNode[]{FakeNode.item(FANG, 1, true)}, "Zulrah", "Obtained: <col=ff0000>1/8</col>", "Zulrah kills: <col=ffffff>100</col>");
		settle();
		waitFor("the note", () -> tracker.panel().noteTexts().contains(
			"Collection Log (Zulrah): Tanzanite fang −1. Nothing changes until you add it."));
		assertEquals(2, boss("zulrah").copies("Tanzanite fang"));
		assertEquals(1, add()[2]);
		// the copy typed in by hand goes, the one seen in game stays
		BossState z = boss("zulrah");
		assertEquals(1, z.copies("Tanzanite fang"));
		assertEquals(Drop.SRC_LIVE, z.drops.get(0).src);
		assertEquals(Integer.valueOf(100), z.drops.get(0).kc);
	}

	@Test
	public void aDismissedListIsForgottenAndAnotherAccountIsNotOfferedIt() throws Exception
	{
		start();
		login(111);
		openKillLog("Boss Kill Log", new String[]{"Zulrah"}, new String[]{"150"});
		tracker.onGameTick();
		HistoryPlanner.Plan plan = offered();
		tracker.host().dismissSync();
		// a page that matches the ledger does not bring the dismissed list back
		pageItems();
		drawPage(new FakeNode[0], "Vorkath");
		settle();
		waitFor("the note", () -> tracker.panel().noteTexts().contains("Collection Log page read: Vorkath. Your ledger matches it."));
		assertEquals(0, tracker.host().applySync(plan)[0]);
		assertEquals(0, boss("zulrah").kc);
		// read again, then another account logs in: the list goes with the first account
		openKillLog("Boss Kill Log", new String[]{"Zulrah"}, new String[]{"150"});
		tracker.onGameTick();
		drain();
		HistoryPlanner.Plan first = offered();
		logout();
		assertNull(tracker.panel().syncPlan());
		login(222);
		drain();
		assertNull(tracker.panel().syncPlan());
		assertEquals(0, tracker.host().applySync(first)[0]);
		assertEquals(0, boss("zulrah").kc);
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

	// ------------------------------------------------------------------ the drop log

	@Test
	public void everyLootGoesInTheDropLogAndAnItemTrackedLaterGetsItsCopies() throws Exception
	{
		start();
		login(111);
		say(vorkathKc(40));
		loot("Vorkath", null, COINS, 40_000, VISAGE, 1);
		say(vorkathKc(41));
		loot("Vorkath", null, COINS, 38_000);
		drain();
		File dropLog = new File(data, "drop-luck-ledger/drops-111.jsonl");
		java.util.List<String> lines = Files.readAllLines(dropLog.toPath(), StandardCharsets.UTF_8);
		assertEquals(2, lines.size());
		assertTrue(lines.get(0), lines.get(0).contains("\"boss\":\"vorkath\",\"kill\":40,\"items\":[[\"Coins\",40000],[\"Skeletal visage\",1]]"));
		// coins are not tracked: the ledger has only the visage
		assertEquals(1, boss("vorkath").drops.size());
		// tracking coins brings in both stacks, at their kills
		tracker.core().ledger.setTracked("vorkath", "Coins", true);
		tracker.host().backfill("vorkath", "Coins");
		waitFor("the coins", () -> boss("vorkath").copies("Coins") == 2);
		assertEquals(Integer.valueOf(41), boss("vorkath").drops.get(2).kc);
		waitFor("the note", () -> tracker.panel().noteTexts().stream().anyMatch(t -> t.startsWith("Coins: 2 copies you got before you tracked it were added")));
		// each account has its own log
		logout();
		login(222);
		say(vorkathKc(3));
		loot("Vorkath", null, COINS, 100);
		drain();
		assertTrue(new File(data, "drop-luck-ledger/drops-222.jsonl").isFile());
		assertEquals(2, Files.readAllLines(dropLog.toPath(), StandardCharsets.UTF_8).size());
		// erasing the ledger erases its log
		tracker.host().eraseDropLog();
		drain();
		assertFalse(new File(data, "drop-luck-ledger/drops-222.jsonl").exists());
		assertTrue(dropLog.isFile());
	}

	@Test
	public void nothingIsLoggedOnAWorldWithItsOwnSave() throws Exception
	{
		start();
		login(111);
		game.standardWorld = false;
		tracker.onGameStateChanged(GameState.LOGGED_IN);
		waitFor("paused", () -> tracker.state() == TrackingState.PAUSED);
		say(vorkathKc(900));
		loot("Vorkath", null, COINS, 1);
		drain();
		assertFalse(new File(data, "drop-luck-ledger/drops-111.jsonl").exists());
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
