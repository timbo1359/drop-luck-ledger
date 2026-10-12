package com.dropluckledger;

import com.dropluckledger.capture.BossIndex;
import com.dropluckledger.capture.Capture;
import com.dropluckledger.capture.KcMessages;
import com.dropluckledger.engine.Catalog;
import com.dropluckledger.engine.Data;
import com.dropluckledger.engine.Data.Boss;
import com.dropluckledger.history.HistoryInput;
import com.dropluckledger.history.HistoryPlanner;
import com.dropluckledger.ledger.BossState;
import com.dropluckledger.ledger.Ledger;
import com.dropluckledger.ledger.LedgerCodec;
import com.dropluckledger.ledger.LedgerSaver;
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
		final LedgerSaver saver;
		final ItemIcons icons;
		final FileStore iconStore;

		Core(Filepath dir, Data data, Gson gson, ScheduledExecutorService executor, Game game)
		{
			this.data = data;
			this.index = new BossIndex(data);
			this.ledger = new Ledger(data);
			this.capture = new Capture(ledger, index);
			this.planner = new HistoryPlanner(ledger, index);
			this.saver = new LedgerSaver(ledger, gson, executor, SAVE_DELAY_MS);
			this.ledger.addListener(saver);
			this.icons = new ItemIcons(game);
			this.iconStore = new FileStore(dir, ICON_FILE);
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
	private boolean killLogLoaded;
	private String houseOwner;
	private boolean offeredHistory;
	private boolean scanChecked;

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
				requestBind(null, TrackingState.LOGGED_OUT);
				break;
			case LOADING:
			case HOPPING:
				houseOwner = null;
				break;
			case LOGGING_IN:
				c.capture.reset();
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
		killLogLoaded = false;
		c.capture.reset();
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
		if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM
			&& type != ChatMessageType.TRADE && type != ChatMessageType.FRIENDSCHATNOTIFICATION)
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
		c.capture.onLoot(new Capture.Loot(name, amount, stacks, hints(bossId, metadata), game.tickCount()));
	}

	/**
	 * Boss settings the game shows along with a reward: the raid level, the delve, the player's skill levels.
	 */
	Map<String, Object> hints(String bossId, Object metadata)
	{
		Map<String, Object> hints = new HashMap<>();
		switch (bossId)
		{
			case "tombs-of-amascut":
				if (metadata instanceof int[] && ((int[]) metadata).length > 0)
				{
					hints.put("raidLevel", ((int[]) metadata)[0]);
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
			case "wintertodt":
				hints.put("fm200", game.experience(Skill.FIREMAKING) >= 200_000_000 ? 1 : 0);
				break;
			default:
				break;
		}
		return hints;
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
				killLogLoaded = true;
				break;
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
			killLogLoaded = false;
			return;
		}
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
		if (killLogLoaded && isMine(houseOwner))
		{
			killLogLoaded = false;
			Map<String, Integer> kills = GameReader.killLog(game);
			if (kills != null && !kills.isEmpty())
			{
				c.ledger.updateHistory(h ->
				{
					h.kc.putAll(kills);
					h.kcTime = c.ledger.now();
				});
				LedgerPanel p = panel;
				if (p != null)
				{
					SwingUtilities.invokeLater(() -> p.historyRead("Boss Kill Log: " + kills.size() + " bosses read"));
				}
			}
		}
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
		if (!isMine(houseOwner))
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
		c.ledger.updateHistory(h ->
		{
			h.log.put(page.title, new LinkedHashMap<>(page.items));
			h.logTime = c.ledger.now();
		});
		LedgerPanel p = panel;
		if (p != null)
		{
			SwingUtilities.invokeLater(() -> p.historyRead("Collection Log page read: " + page.title));
		}
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
				HistoryInput in = HistorySources.collect(c.ledger, game, gson, c.index);
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
