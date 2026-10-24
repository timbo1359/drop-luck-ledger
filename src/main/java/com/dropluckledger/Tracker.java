package com.dropluckledger;

import com.dropluckledger.capture.BossIndex;
import com.dropluckledger.capture.Capture;
import com.dropluckledger.capture.KcMessages;
import com.dropluckledger.engine.Catalog;
import com.dropluckledger.engine.Data;
import com.dropluckledger.engine.Data.Boss;
import com.dropluckledger.history.Backfill;
import com.dropluckledger.history.Catchup;
import com.dropluckledger.history.HistoryInput;
import com.dropluckledger.history.HistoryPlanner;
import com.dropluckledger.history.LoggedLoot;
import com.dropluckledger.ledger.BossState;
import com.dropluckledger.ledger.Ledger;
import com.dropluckledger.ledger.LedgerCodec;
import com.dropluckledger.ledger.LedgerSaver;
import com.dropluckledger.plugin.DropLog;
import com.dropluckledger.plugin.DropMessages;
import com.dropluckledger.plugin.FileStore;
import com.dropluckledger.plugin.Game;
import com.dropluckledger.plugin.GameReader;
import com.dropluckledger.plugin.HistorySources;
import com.dropluckledger.plugin.ItemIcons;
import com.dropluckledger.plugin.TrackingState;
import com.dropluckledger.ui.LedgerPanel;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.awt.Component;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything the plugin does, once RuneLite has told it what happened: reads the boss data, keeps one ledger per account,
 * records kill counts and drops as they come, reads the game's own records when the player opens them, and drives the side
 * panel.  It only ever sees the game through {@link Game}; the plugin class turns RuneLite's events into calls on this class.
 */
final class Tracker
{
	private static final Logger log = LoggerFactory.getLogger(Tracker.class);

	private static final String DATA_RESOURCE = "/com/dropluckledger/data.json";
	private static final String COLOSSEUM = "fortis-colosseum";
	/**
	 * The game's message after each wave of the Fortis Colosseum ("Wave 5 completed! Wave duration: 2:03.60").
	 */
	private static final java.util.regex.Pattern WAVE_COMPLETED = java.util.regex.Pattern.compile("^Wave (\\d+) completed!");
	/**
	 * How often (in ticks) to look again whether the Loot Tracker plugin is on.
	 */
	private static final int LOOT_TRACKER_CHECK_TICKS = 50;
	private static final String ICON_FILE = "item-ids.json";
	private static final long SAVE_DELAY_MS = 1500;
	private static final long ICON_SAVE_PERIOD_S = 30;
	private static final long ICON_RESCAN_MS = 30L * 24 * 60 * 60 * 1000;

	private final Game game;
	private final DropLuckLedgerConfig config;
	private final Filepath dir;
	private final Gson gson;
	private final ScheduledExecutorService executor;

	/**
	 * Everything that exists once the boss data has been read.
	 */
	static final class Core
	{
		final Data data;
		final BossIndex index;
		final Ledger ledger;
		final Capture capture;
		final HistoryPlanner planner;
		final Catchup catchup;
		final LedgerSaver saver;
		final ItemIcons icons;
		final FileStore iconStore;
		final DropLog dropLog;

		Core(Filepath dir, Data data, Gson gson, ScheduledExecutorService executor, Game game)
		{
			this.data = data;
			this.index = new BossIndex(data);
			this.ledger = new Ledger(data);
			this.capture = new Capture(ledger, index);
			this.planner = new HistoryPlanner(ledger, index);
			this.catchup = new Catchup(ledger, capture, planner, index);
			this.saver = new LedgerSaver(ledger, gson, executor, SAVE_DELAY_MS);
			this.ledger.addListener(saver);
			this.icons = new ItemIcons(game);
			this.iconStore = new FileStore(dir, ICON_FILE);
			this.dropLog = new DropLog(dir, executor);
			this.capture.setLootLog((t, bossId, killNo, items, snap) ->
			{
				List<LoggedLoot.Item> all = new ArrayList<>();
				for (Capture.Stack st : items)
				{
					all.add(new LoggedLoot.Item(st.name, st.qty));
				}
				dropLog.add(new LoggedLoot(t, bossId, killNo, all, snap));
			});
		}
	}

	private volatile Core core;
	private volatile TrackingState state = TrackingState.STARTING;
	/**
	 * Counts account changes; a load that finds the number moved on gives up.
	 */
	private volatile int bindGeneration;
	private String boundKey;
	/**
	 * Held while tracking is switched on or off and while the loaded data is handed over, so a load that finishes late never
	 * leaves anything running behind a plugin that was turned off.
	 */
	private final Object lifecycle = new Object();
	private volatile ExecutorService analysis;
	private Future<?> iconSaver;
	private volatile LedgerPanel panel;

	// widgets that were opened and are read on the next game tick
	private boolean adventureLogLoaded;
	/**
	 * The ledger as it was when the Boss Kill Log opened, until the log is read; null when none opened.
	 */
	private Catchup.Stamp killLogOpened;
	private String houseOwner;
	/**
	 * Game ticks a collection log page is given to settle before it is checked: the server has the game draw a page and sends its
	 * numbers and items around the same time, sometimes just after (then the page is drawn again, or its counts change under it).  A
	 * page the player leaves sooner is not checked, so clicking quickly through the pages checks only the one stopped at.
	 */
	static final int SETTLE_TICKS = 2;
	/**
	 * The player variables a collection log page draws its counts from (the same three for every page).
	 */
	private static final int[] PAGE_COUNT_VARPS = {VarPlayerID.COLLECTION_CATEGORY_COUNT, VarPlayerID.COLLECTION_CATEGORY_COUNT2,
		VarPlayerID.COLLECTION_CATEGORY_COUNT3};

	/**
	 * A collection log page as it was drawn, waiting to settle.
	 */
	private static final class DrawnPage
	{
		final GameReader.LogPage page;
		final int tick;
		/**
		 * The ledger as it was when the page was drawn.
		 */
		final Catchup.Stamp stamp;
		/**
		 * The values of {@link #PAGE_COUNT_VARPS} when it was drawn.
		 */
		final int[] varps;

		DrawnPage(GameReader.LogPage page, int tick, Catchup.Stamp stamp, int[] varps)
		{
			this.page = page;
			this.tick = tick;
			this.stamp = stamp;
			this.varps = varps;
		}
	}

	private DrawnPage drawnPage;
	private boolean offeredHistory;
	private boolean scanChecked;
	/**
	 * Whether RuneLite's Loot Tracker plugin is on, looked at every few ticks.
	 */
	private boolean lootTrackerOn = true;
	private int lootTrackerCheckedTick = Integer.MIN_VALUE / 2;
	/**
	 * The last Fortis Colosseum wave completed (0: none since the last claim): the wave a claim is made at.
	 */
	private int colosseumWave;

	/**
	 * @param dir      the plugin's data folder
	 * @param executor RuneLite's shared background threads
	 */
	Tracker(Game game, DropLuckLedgerConfig config, Filepath dir, Gson gson, ScheduledExecutorService executor)
	{
		this.game = game;
		this.config = config;
		this.dir = dir;
		this.gson = gson;
		this.executor = executor;
	}

	// ------------------------------------------------------------------ lifecycle

	// for the tests
	TrackingState state()
	{
		return state;
	}

	Core core()
	{
		return core;
	}

	LedgerPanel panel()
	{
		return panel;
	}

	LedgerPanel.Host host()
	{
		return new PanelHost();
	}

	/**
	 * Builds the side panel and starts reading the boss data in the background.
	 *
	 * @return the side panel, to put in RuneLite's toolbar
	 */
	LedgerPanel start()
	{
		final ExecutorService owner;
		synchronized (lifecycle)
		{
			state = TrackingState.STARTING;
			boundKey = null;
			offeredHistory = false;
			scanChecked = false;
			owner = Executors.newSingleThreadExecutor(r ->
			{
				Thread t = new Thread(r, "drop-luck-ledger-analysis");
				t.setDaemon(true);
				return t;
			});
			analysis = owner;
		}
		final LedgerPanel p = new LedgerPanel(new PanelHost(), owner);
		panel = p;
		executor.execute(() -> load(owner));
		return p;
	}

	/**
	 * Stops recording and writes what is waiting to disk (in the background).
	 */
	void stop()
	{
		final Core c;
		synchronized (lifecycle)
		{
			bindGeneration++;
			boundKey = null;
			c = core;
			core = null;
			state = TrackingState.STARTING;
			if (iconSaver != null)
			{
				iconSaver.cancel(false);
				iconSaver = null;
			}
			if (analysis != null)
			{
				analysis.shutdownNow();
				analysis = null;
			}
		}
		final LedgerPanel p = panel;
		panel = null;
		if (p != null)
		{
			p.detach();
		}
		if (c != null)
		{
			// the last changes still waiting for the saver are written on the shared background thread
			executor.execute(() -> finish(c));
		}
	}

	/**
	 * Reads the boss data, then builds everything else.  Runs on the background thread.
	 */
	private void load(ExecutorService owner)
	{
		try
		{
			Data data;
			try (InputStream in = Tracker.class.getResourceAsStream(DATA_RESOURCE))
			{
				if (in == null)
				{
					throw new IOException("boss data is missing from the plugin");
				}
				data = Data.load(gson, in);
			}
			if (owner != analysis)
			{
				return; // tracking was switched off while the data was loading
			}
			final Core c = new Core(dir, data, gson, executor, game);
			readIcons(c);
			c.capture.setListener((bossId, item, killNo) -> onDropLogged(c, bossId, item, killNo));
			synchronized (lifecycle)
			{
				if (owner != analysis)
				{
					return;
				}
				core = c;
				iconSaver = executor.scheduleWithFixedDelay(() -> saveIcons(c), ICON_SAVE_PERIOD_S, ICON_SAVE_PERIOD_S, TimeUnit.SECONDS);
				state = TrackingState.LOGGED_OUT;
			}
			SwingUtilities.invokeLater(() ->
			{
				LedgerPanel p = panel;
				if (p != null && core == c)
				{
					p.attach(c.ledger, c.index, c.icons, c.planner);
					p.stateChanged();
				}
			});
			game.invokeLater(() ->
			{
				if (core == c && game.isLoggedIn())
				{
					bindAccount();
				}
			});
		}
		catch (IOException | RuntimeException ex)
		{
			log.warn("Drop Luck Ledger could not start", ex);
		}
	}

	/**
	 * Writes what is waiting and stops the saver (background thread).
	 */
	private void finish(Core c)
	{
		c.saver.setStore(null);
		c.ledger.removeListener(c.saver);
		c.capture.reset(); // loot still waiting for its kill-count message is logged as it is
		c.dropLog.flush();
		saveIcons(c);
	}

	// ------------------------------------------------------------------ account

	void onGameStateChanged(GameState gameState)
	{
		final Core c = core;
		if (c == null)
		{
			return;
		}
		switch (gameState)
		{
			case LOGGED_IN:
				bindAccount();
				break;
			case LOGIN_SCREEN:
				drawnPage = null;
				requestBind(null, TrackingState.LOGGED_OUT);
				break;
			case LOADING:
				houseOwner = null;
				break;
			case HOPPING:
			case CONNECTION_LOST:
				// the server sends the player's values afresh: a page drawn before is not checked against them
				houseOwner = null;
				drawnPage = null;
				break;
			case LOGGING_IN:
				drawnPage = null;
				c.capture.reset();
				colosseumWave = 0;
				break;
			default:
				break;
		}
	}

	/**
	 * Decides which ledger is in use from the account that is logged in and the kind of world.
	 */
	void bindAccount()
	{
		final long hash = game.accountHash();
		if (!game.isStandardWorld())
		{
			requestBind(null, TrackingState.PAUSED);
		}
		else if (hash == -1L)
		{
			requestBind(null, TrackingState.LOGGED_OUT);
		}
		else
		{
			requestBind(Long.toString(hash), TrackingState.LOADING);
		}
	}

	/**
	 * Switches to the ledger of another account (or to none).  The load happens on the background thread; events are
	 * ignored until it is done.
	 *
	 * @param key the account, or null for none
	 * @param why what to tell the player when there is none
	 */
	private void requestBind(String key, TrackingState why)
	{
		final Core c = core;
		if (c == null)
		{
			return;
		}
		if (Objects.equals(key, boundKey) && (key != null || state == why))
		{
			return;
		}
		boundKey = key;
		final int generation = ++bindGeneration;
		offeredHistory = false;
		houseOwner = null;
		adventureLogLoaded = false;
		killLogOpened = null;
		drawnPage = null;
		c.capture.reset();
		c.catchup.clear();
		// what was waiting goes to the account it came from; from now on the loot is logged for the new one (or not at all)
		c.dropLog.setAccount(key == null ? null : Long.parseLong(key));
		if (key == null)
		{
			state = why;
			executor.execute(() ->
			{
				if (generation != bindGeneration)
				{
					return;
				}
				c.saver.setStore(null);
				c.ledger.replaceWith(new Ledger(c.data));
				stateChanged();
			});
			return;
		}
		state = TrackingState.LOADING;
		stateChanged();
		executor.execute(() ->
		{
			if (generation != bindGeneration)
			{
				return;
			}
			FileStore store = new FileStore(dir, FileStore.ledgerName(Long.parseLong(key)));
			c.saver.setStore(null);
			final boolean readable = c.saver.loadInto(store);
			if (generation != bindGeneration)
			{
				return;
			}
			c.saver.setStore(store);
			if (!readable)
			{
				message("Your saved ledger could not be read, so it was set aside and you are starting from an empty one. "
					+ "The old file is in the plugin's folder (its name ends in .unreadable).");
			}
			game.invokeLater(() ->
			{
				if (generation == bindGeneration && core == c)
				{
					c.capture.reset();
					state = TrackingState.TRACKING;
					stateChanged();
					afterBind(c);
				}
			});
		});
	}

	private void stateChanged()
	{
		SwingUtilities.invokeLater(() ->
		{
			LedgerPanel p = panel;
			if (p != null)
			{
				p.stateChanged();
			}
		});
	}

	/**
	 * Client thread, once a ledger is ready: starts the item picture scan and looks for history to offer.
	 */
	private void afterBind(Core c)
	{
		if (!scanChecked)
		{
			scanChecked = true;
			if (System.currentTimeMillis() - c.icons.scannedAt() > ICON_RESCAN_MS)
			{
				Set<String> names = new LinkedHashSet<>();
				for (Boss b : c.data.bosses)
				{
					names.addAll(Catalog.defaultTracked(b));
				}
				c.icons.startScan(names);
			}
		}
		if (!offeredHistory && config.importOnLogin() && c.ledger.activeBosses().isEmpty())
		{
			offeredHistory = true;
			planHistory(c, plan ->
			{
				LedgerPanel p = panel;
				if (p != null && !plan.isEmpty())
				{
					p.offerHistory(plan);
				}
			});
		}
	}

	// ------------------------------------------------------------------ game messages and loot

	void onChatMessage(ChatMessageType type, String message)
	{
		final Core c = core;
		if (c == null || state != TrackingState.TRACKING)
		{
			return;
		}
		if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM && type != ChatMessageType.TRADE)
		{
			return;
		}
		final int tick = game.tickCount();
		final KcMessages.Kc kc = KcMessages.parseKillCount(message);
		if (kc != null)
		{
			c.capture.onKillCount(kc.label, kc.count, tick);
			return;
		}
		java.util.regex.Matcher wave = WAVE_COMPLETED.matcher(KcMessages.stripTags(message));
		if (wave.find())
		{
			colosseumWave = Integer.parseInt(wave.group(1));
		}
		final String logged = KcMessages.collectionLogItem(message);
		if (logged != null)
		{
			c.capture.onCollectionLog(logged, tick);
		}
		if (KcMessages.isPetMessage(message))
		{
			c.capture.onPetMessage(tick);
		}
	}

	/**
	 * Loot from a kill or an event, as RuneLite's Loot Tracker reports it.
	 *
	 * @param itemIds    the ids of the items received
	 * @param quantities how many of each (same length)
	 * @param metadata   what the Loot Tracker knows about the reward (the raid level, the delve, ...), or null
	 */
	void onLoot(LootRecordType type, String name, int amount, int[] itemIds, int[] quantities, Object metadata)
	{
		final Core c = core;
		if (c == null || state != TrackingState.TRACKING)
		{
			return;
		}
		if (type != LootRecordType.NPC && type != LootRecordType.EVENT)
		{
			return;
		}
		final String bossId = c.index.loot(name);
		if (bossId == null)
		{
			return;
		}
		List<Capture.Stack> stacks = new ArrayList<>();
		for (int i = 0; i < itemIds.length; i++)
		{
			final int id = game.canonicalItemId(itemIds[i]);
			final String item = game.itemName(id);
			if (item != null)
			{
				c.icons.learn(item, id);
				stacks.add(new Capture.Stack(item, quantities[i]));
			}
		}
		c.capture.onLoot(new Capture.Loot(name, amount, stacks, hints(bossId, stacks, metadata), game.tickCount()));
	}

	/**
	 * Boss settings the game shows along with a reward: the raid level, the raid points and the team, the delve, the wave, the
	 * player's skill levels, whether the player was the MVP, how many brothers or moons were defeated, whether the loot was the player's.
	 *
	 * @param stacks the items, named as the game names them
	 */
	Map<String, Object> hints(String bossId, List<Capture.Stack> stacks, Object metadata)
	{
		Map<String, Object> hints = new HashMap<>();
		switch (bossId)
		{
			case "chambers-of-xeric":
			{
				// the player's own points this raid, which the unique chance depends on (the game keeps them until the raid is left)
				int points = game.varp(VarPlayerID.RAIDS_PLAYERSCORE);
				if (points > 0)
				{
					hints.put("points", points);
				}
				break;
			}
			case "theatre-of-blood":
			{
				// the players in the raid, as RuneLite's own plugins count them
				int team = 0;
				for (int v : new int[] {VarbitID.TOB_CLIENT_P0, VarbitID.TOB_CLIENT_P1, VarbitID.TOB_CLIENT_P2, VarbitID.TOB_CLIENT_P3, VarbitID.TOB_CLIENT_P4})
				{
					team += Math.min(game.varbit(v), 1);
				}
				if (team > 0)
				{
					hints.put("team", team);
				}
				break;
			}
			case "tombs-of-amascut":
				// the Loot Tracker gives the raid level, the team size and the damage done
				if (metadata instanceof int[] && ((int[]) metadata).length > 0)
				{
					hints.put("raidLevel", ((int[]) metadata)[0]);
				}
				break;
			case "scurrius":
				// only the MVP (or a solo player) gets the rat meat
				hints.put("mvp", has(stacks, "Raw rat meat") ? 1 : 0);
				break;
			case "nex":
			case "the-nightmare":
				// big bones only drop for the MVP
				hints.put("mvp", has(stacks, "Big bones") ? 1 : 0);
				break;
			case "revenant-maledictus":
			{
				// a player who did not deal the most damage gets only blighted supplies
				boolean onlySupplies = !stacks.isEmpty();
				for (Capture.Stack st : stacks)
				{
					onlySupplies &= st.name.startsWith("Blighted ");
				}
				hints.put("looted", onlySupplies ? 0 : 100);
				break;
			}
			case "brutus":
			case "bryophyta":
				hints.put("members", game.isMembersWorld() ? 1 : 0);
				break;
			case "barrows":
			{
				int brothers = 0;
				for (int v : new int[] {VarbitID.BARROWS_KILLED_AHRIM, VarbitID.BARROWS_KILLED_DHAROK, VarbitID.BARROWS_KILLED_GUTHAN,
					VarbitID.BARROWS_KILLED_KARIL, VarbitID.BARROWS_KILLED_TORAG, VarbitID.BARROWS_KILLED_VERAC})
				{
					if (game.varbit(v) > 0)
					{
						brothers++;
					}
				}
				if (brothers > 0)
				{
					hints.put("brothers", brothers);
				}
				break;
			}
			case "moons-of-peril":
			{
				int blood = game.varbit(VarbitID.PMOON_BOSS_BLOOD_DEAD);
				int blue = game.varbit(VarbitID.PMOON_BOSS_BLUE_DEAD);
				int eclipse = game.varbit(VarbitID.PMOON_BOSS_ECLIPSE_DEAD);
				if (blood > 0 || blue > 0 || eclipse > 0)
				{
					hints.put("blood", blood > 0 ? 1 : 0);
					hints.put("blue", blue > 0 ? 1 : 0);
					hints.put("eclipse", eclipse > 0 ? 1 : 0);
				}
				break;
			}
			case COLOSSEUM:
				// the chest is claimed between waves: the last wave completed is the wave claimed at
				if (colosseumWave > 0)
				{
					hints.put("waves", colosseumWave);
					colosseumWave = 0;
				}
				break;
			case "doom-of-mokhaiotl":
				if (metadata instanceof Integer)
				{
					hints.put("delve", metadata);
				}
				break;
			case "tempoross":
				hints.put("fishing", game.realLevel(Skill.FISHING));
				break;
			case "hespori":
				hints.put("farming", game.realLevel(Skill.FARMING));
				break;
			default:
				break;
		}
		return hints;
	}

	private static boolean has(List<Capture.Stack> stacks, String item)
	{
		for (Capture.Stack st : stacks)
		{
			if (st.name.equalsIgnoreCase(item))
			{
				return true;
			}
		}
		return false;
	}

	private void onDropLogged(Core c, String bossId, String item, int killNo)
	{
		if (!config.chatMessages())
		{
			return;
		}
		BossState s = c.ledger.copyOf(bossId);
		game.gameMessage(DropMessages.build(c.data, bossId, s, item, killNo));
	}

	// ------------------------------------------------------------------ history from the game's own screens

	/**
	 * An interface opened.
	 */
	void onWidgetLoaded(int groupId)
	{
		switch (groupId)
		{
			case InterfaceID.MENU:
			case InterfaceID.MENU_NEW:
				adventureLogLoaded = true;
				break;
			case InterfaceID.KILL_LOG:
			{
				// the server sends the counts as the log opens: a kill the ledger takes after this is not in them
				Core c = core;
				killLogOpened = c == null ? null : c.catchup.stamp();
				break;
			}
			default:
				break;
		}
	}

	void onGameTick()
	{
		final Core c = core;
		if (c == null)
		{
			return;
		}
		if (state != TrackingState.TRACKING)
		{
			adventureLogLoaded = false;
			killLogOpened = null;
			return;
		}
		final int tick = game.tickCount();
		if (tick - lootTrackerCheckedTick >= LOOT_TRACKER_CHECK_TICKS || tick < lootTrackerCheckedTick)
		{
			lootTrackerCheckedTick = tick;
			boolean on = game.isLootTrackerOn();
			if (!on && lootTrackerOn)
			{
				message("RuneLite's Loot Tracker plugin is off, so drops are not recorded automatically (kill counts still are). "
					+ "Turn it on in RuneLite's plugin list to record drops.");
			}
			lootTrackerOn = on;
		}
		c.capture.onTick(tick, lootTrackerOn);
		if (c.icons.isScanning() && c.icons.scanTick(System.currentTimeMillis()))
		{
			saveIcons(c);
		}
		if (adventureLogLoaded)
		{
			adventureLogLoaded = false;
			String owner = GameReader.adventureLogOwner(game);
			if (owner != null)
			{
				houseOwner = owner;
			}
		}
		if (killLogOpened != null)
		{
			Catchup.Stamp opened = killLogOpened;
			killLogOpened = null;
			Map<String, Integer> kills = isMine(houseOwner) ? GameReader.killLog(game) : null;
			if (kills != null && !kills.isEmpty())
			{
				// the log comes in one piece from the server: it is checked at once, and nothing changes until the player agrees
				c.catchup.addKillLog(kills, opened);
				offerSync(c, "Boss Kill Log read");
			}
		}
		if (drawnPage != null && tick - drawnPage.tick >= SETTLE_TICKS)
		{
			DrawnPage d = drawnPage;
			drawnPage = null;
			settled(c, d);
		}
	}

	/**
	 * A collection log page has had time to settle: its items are checked against the ledger if they are the ones the game holds for
	 * the page now.
	 */
	private void settled(Core c, DrawnPage d)
	{
		GameReader.LogPage page = d.page;
		// the boss kill counts under the title, as the game holds them now
		Map<String, Integer> shown = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> e : page.counts.entrySet())
		{
			if (c.index.kcShown(e.getKey()) != null)
			{
				shown.put(e.getKey(), e.getValue());
			}
		}
		Map<String, Integer> counts = GameReader.settledCounts(shown, d.varps, pageCountVarps());
		boolean countsSettled = counts.size() == shown.size();
		boolean bodyTypeSwap = game.varbit(VarbitID.COLLECTION_PLAYER_BODYTYPE) == 1;
		boolean itemsSettled = GameReader.itemsMatch(page, game.inventory(InventoryID.COLLECTION_TRANSMIT), bodyTypeSwap);
		if (!countsSettled || !itemsSettled)
		{
			log.debug("Collection Log {}: {} of {} counts settled, items {}", page.title, counts.size(), shown.size(), itemsSettled ? "settled" : "not settled");
		}
		// what settled is checked: a count that did not leaves its boss as it is, and items that did not take part at all
		if (!itemsSettled && (counts.isEmpty() || !countsSettled))
		{
			historyRead("Collection Log, " + page.title + ": it was still loading, so it was not checked. Open it again to check it.");
			return;
		}
		Collection<String> listed = itemsSettled ? page.ids.keySet() : Collections.<String>emptyList();
		if (!c.catchup.unchangedSince(d.stamp, listed, counts.keySet()))
		{
			// a kill or a drop came while the page opened (or a list was added): its numbers may be older than the ledger's
			historyRead("Collection Log, " + page.title + ": your ledger changed while it opened, so it was not checked. Open it again to check it.");
			return;
		}
		c.catchup.addPage(page.title, counts, itemsSettled ? page.items : Collections.<String, Integer>emptyMap(), listed, d.stamp);
		offerSync(c, !itemsSettled ? "Collection Log, " + page.title + ": its items were still loading, so only its kill count was checked"
			: !countsSettled ? "Collection Log, " + page.title + ": its kill count was still loading, so only its items were checked"
			: "Collection Log page read: " + page.title);
	}

	private int[] pageCountVarps()
	{
		int[] v = new int[PAGE_COUNT_VARPS.length];
		for (int i = 0; i < v.length; i++)
		{
			v[i] = game.varp(PAGE_COUNT_VARPS[i]);
		}
		return v;
	}

	/**
	 * Works out what the game's records read so far would change and offers it in the panel; when nothing would change, says the
	 * page was read.
	 *
	 * @param read what was just read, for the note when the ledger already matches
	 */
	private void offerSync(Core c, String read)
	{
		HistoryPlanner.Plan plan = c.catchup.plan();
		LedgerPanel p = panel;
		if (p == null)
		{
			return;
		}
		if (Catchup.worthShowing(plan))
		{
			String text = Catchup.describe(plan);
			SwingUtilities.invokeLater(() -> p.syncReady(plan, text));
		}
		else
		{
			historyRead(read + ". Your ledger matches it.");
		}
	}

	private void historyRead(String text)
	{
		SwingUtilities.invokeLater(() ->
		{
			LedgerPanel p = panel;
			if (p != null)
			{
				p.historyRead(text);
			}
		});
	}

	/**
	 * A game script finished running.
	 */
	void onScriptPostFired(int scriptId)
	{
		final Core c = core;
		if (c == null || state != TrackingState.TRACKING || scriptId != ScriptID.COLLECTION_DRAW_LIST)
		{
			return;
		}
		if (!ownLogOpen())
		{
			return;
		}
		GameReader.LogPage page = GameReader.collectionPage(game);
		if (page == null)
		{
			return;
		}
		for (Map.Entry<String, Integer> e : page.ids.entrySet())
		{
			c.icons.learn(e.getKey(), e.getValue());
		}
		// checked once it has settled; a page drawn again before then (the next page, or this one with its items) replaces it
		drawnPage = new DrawnPage(page, game.tickCount(), c.catchup.stamp(), pageCountVarps());
	}

	/**
	 * True when the collection log on screen is the player's own: not one opened from the book in someone else's house.
	 */
	private boolean ownLogOpen()
	{
		if (game.varbit(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN) == 0)
		{
			return true;
		}
		return houseOwner != null && houseOwner.equals(game.playerName());
	}

	/**
	 * True unless the open adventure log belongs to another player.
	 */
	private boolean isMine(String owner)
	{
		return owner == null || owner.equals(game.playerName());
	}

	/**
	 * Gathers everything the game and RuneLite know about the player's history and works out what it would add.  Client
	 * thread; the result is handed to the callback on the Swing thread.
	 */
	private void planHistory(Core c, Consumer<HistoryPlanner.Plan> then)
	{
		game.invoke(() ->
		{
			HistoryPlanner.Plan plan;
			try
			{
				HistoryInput in = HistorySources.collect(game, gson, c.index);
				plan = c.planner.plan(in);
			}
			catch (RuntimeException ex)
			{
				log.warn("Could not gather history", ex);
				return;
			}
			SwingUtilities.invokeLater(() -> then.accept(plan));
		});
	}

	// ------------------------------------------------------------------ shutdown and icons

	/**
	 * RuneLite is closing: writes everything now.
	 *
	 * @return the write, for RuneLite to wait for, or null when there is nothing to write
	 */
	Future<?> flushForShutdown()
	{
		final Core c = core;
		if (c == null)
		{
			return null;
		}
		return executor.submit(() ->
		{
			c.saver.flushNow();
			c.dropLog.flush();
			saveIcons(c);
		});
	}

	private void readIcons(Core c)
	{
		try
		{
			String text = c.iconStore.read();
			if (text == null)
			{
				return;
			}
			JsonObject root = new JsonParser().parse(text).getAsJsonObject();
			Map<String, Integer> ids = new HashMap<>();
			JsonObject o = root.getAsJsonObject("ids");
			if (o != null)
			{
				for (Map.Entry<String, JsonElement> e : o.entrySet())
				{
					ids.put(e.getKey(), e.getValue().getAsInt());
				}
			}
			long scannedAt = root.has("scannedAt") ? root.get("scannedAt").getAsLong() : 0;
			c.icons.restore(ids, scannedAt);
		}
		catch (IOException | JsonParseException | IllegalStateException | ClassCastException | NumberFormatException ex)
		{
			log.debug("Could not read the saved item pictures; they will be found again", ex);
		}
	}

	private void saveIcons(Core c)
	{
		if (!c.icons.isDirty())
		{
			return;
		}
		try
		{
			JsonObject root = new JsonObject();
			root.addProperty("v", 1);
			root.addProperty("scannedAt", c.icons.scannedAt());
			JsonObject o = new JsonObject();
			for (Map.Entry<String, Integer> e : c.icons.snapshot().entrySet())
			{
				o.addProperty(e.getKey(), e.getValue());
			}
			root.add("ids", o);
			c.iconStore.write(gson.toJson(root));
		}
		catch (IOException | RuntimeException ex)
		{
			log.debug("Could not save the item pictures", ex);
		}
	}

	// ------------------------------------------------------------------ what the side panel can ask for

	private final class PanelHost implements LedgerPanel.Host
	{
		@Override
		public TrackingState state()
		{
			return state;
		}

		@Override
		public void planHistory(Consumer<HistoryPlanner.Plan> then)
		{
			Core c = core;
			if (c != null && state == TrackingState.TRACKING)
			{
				Tracker.this.planHistory(c, then);
			}
		}

		@Override
		public int[] applySync(HistoryPlanner.Plan plan)
		{
			Core c = core;
			if (c == null || state != TrackingState.TRACKING)
			{
				return new int[4];
			}
			int[] done = c.catchup.apply(plan);
			// pages read after the list was made make the next one
			HistoryPlanner.Plan next = c.catchup.plan();
			LedgerPanel p = panel;
			if (p != null && Catchup.worthShowing(next))
			{
				String text = Catchup.describe(next);
				SwingUtilities.invokeLater(() -> p.syncReady(next, text));
			}
			return done;
		}

		@Override
		public void dismissSync()
		{
			Core c = core;
			if (c != null)
			{
				c.catchup.clear();
			}
		}

		@Override
		public void exportBackup(Component parent)
		{
			final Core c = core;
			if (c == null)
			{
				return;
			}
			final Filepath file = game.chooseFileToSave(parent, "Export Drop Luck Ledger backup", "drop-luck-ledger-backup.json", "Ledger backup (*.json)", "json");
			if (file == null)
			{
				return;
			}
			final String json = LedgerCodec.toJson(gson, c.ledger);
			executor.execute(() ->
			{
				String result;
				try
				{
					FileStore.writeText(file, json);
					result = "Backup saved.";
				}
				catch (IOException ex)
				{
					log.warn("Could not write the backup", ex);
					result = "Could not save the backup: " + ex.getMessage();
				}
				message(result);
			});
		}

		@Override
		public void importBackup(Component parent, Consumer<Ledger> confirm)
		{
			final Core c = core;
			if (c == null)
			{
				return;
			}
			final Filepath file = game.chooseFileToOpen(parent, "Import a Drop Luck Ledger backup", "Ledger backup (*.json)", "json");
			if (file == null)
			{
				return;
			}
			executor.execute(() ->
			{
				try
				{
					Ledger backup = LedgerCodec.fromJson(c.data, FileStore.readText(file));
					SwingUtilities.invokeLater(() -> confirm.accept(backup));
				}
				catch (IOException | LedgerCodec.FormatException | RuntimeException ex)
				{
					log.debug("Could not read the backup", ex);
					message("That file is not a Drop Luck Ledger backup (" + ex.getMessage() + ").");
				}
			});
		}

		@Override
		public void replaceLedger(Ledger backup)
		{
			Core c = core;
			if (c != null && state == TrackingState.TRACKING)
			{
				executor.execute(() -> c.ledger.replaceWith(backup));
			}
		}

		@Override
		public void backfill(String bossId, String item)
		{
			final Core c = core;
			if (c == null || state != TrackingState.TRACKING)
			{
				return;
			}
			executor.execute(() ->
			{
				try
				{
					Backfill.Result r = Backfill.apply(c.ledger, c.index, bossId, item, c.dropLog.read(bossId));
					if (!r.isEmpty())
					{
						done(backfillText(item, r));
					}
				}
				catch (IOException ex)
				{
					log.warn("Could not read the drop log", ex);
				}
			});
		}

		@Override
		public void eraseDropLog()
		{
			final Core c = core;
			if (c == null || state != TrackingState.TRACKING)
			{
				return;
			}
			executor.execute(() ->
			{
				try
				{
					c.dropLog.erase();
				}
				catch (IOException ex)
				{
					log.warn("Could not delete the drop log", ex);
				}
			});
		}
	}

	/**
	 * What the drop log brought in for an item just tracked: "Tanzanite fang: 2 copies you got before you tracked it were added from
	 * the drop log, at the kills they came on."
	 */
	static String backfillText(String item, Backfill.Result r)
	{
		StringBuilder b = new StringBuilder(item).append(": ");
		if (r.added > 0)
		{
			b.append(r.added == 1 ? "1 copy" : r.added + " copies").append(" you got before you tracked it ").append(r.added == 1 ? "was" : "were")
				.append(" added from the drop log, at the kill").append(r.added == 1 ? " it" : "s they").append(" came on");
		}
		if (r.numbered > 0)
		{
			b.append(r.added > 0 ? "; " : "").append(r.numbered == 1 ? "1 copy" : r.numbered + " copies").append(" from your game history now ")
				.append(r.numbered == 1 ? "has its" : "have their").append(" kill number");
		}
		return b.append('.').toString();
	}

	/**
	 * Shows a short note at the top of the side panel that something was done.
	 */
	private void done(String text)
	{
		SwingUtilities.invokeLater(() ->
		{
			LedgerPanel p = panel;
			if (p != null)
			{
				p.showDone(text);
			}
		});
	}

	/**
	 * Shows a note at the top of the side panel.
	 */
	private void message(String text)
	{
		SwingUtilities.invokeLater(() ->
		{
			LedgerPanel p = panel;
			if (p != null)
			{
				p.showMessage(text);
			}
		});
	}
}
