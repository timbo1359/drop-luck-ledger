package com.dropluckledger;

import com.dropluckledger.plugin.RuneLiteGame;
import com.dropluckledger.ui.LedgerPanel;
import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.Collection;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

/**
 * Records boss kills and drops and shows how lucky or dry the player is.  This class only connects RuneLite to
 * {@link Tracker}, which does the work.
 */
@PluginDescriptor(
	name = "Drop Luck Ledger",
	description = "Records your boss kills and drops and shows how lucky or dry you are, from the real drop tables",
	tags = {"boss", "drop", "drops", "luck", "dry", "rate", "kc", "kill", "count", "loot", "unique", "collection", "log", "pet"},
	internalName = "drop-luck-ledger"
)
public class DropLuckLedgerPlugin extends Plugin
{
	private static final String ICON_RESOURCE = "/com/dropluckledger/icon.png";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ConfigManager configManager;

	@Inject
	private DropLuckLedgerConfig config;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private Gson gson;

	private volatile Tracker tracker;
	private NavigationButton navButton;

	@Provides
	DropLuckLedgerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(DropLuckLedgerConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		final Tracker t = new Tracker(new RuneLiteGame(client, clientThread, itemManager, configManager, chatMessageManager),
			config, getPluginDirectory(), gson, executor);
		final LedgerPanel panel = t.start();
		tracker = t;

		final BufferedImage icon = ImageUtil.loadImageResource(DropLuckLedgerPlugin.class, ICON_RESOURCE);
		navButton = NavigationButton.builder()
			.tooltip("Drop Luck Ledger")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
	}

	@Override
	protected void shutDown() throws Exception
	{
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		final Tracker t = tracker;
		tracker = null;
		if (t != null)
		{
			t.stop();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		final Tracker t = tracker;
		if (t != null)
		{
			t.onGameStateChanged(event.getGameState());
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		final Tracker t = tracker;
		if (t != null)
		{
			t.onChatMessage(event.getType(), event.getMessage());
		}
	}

	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		final Tracker t = tracker;
		if (t == null)
		{
			return;
		}
		final Collection<ItemStack> items = event.getItems();
		final int[] ids = new int[items.size()];
		final int[] quantities = new int[items.size()];
		int i = 0;
		for (ItemStack stack : items)
		{
			ids[i] = stack.getId();
			quantities[i] = stack.getQuantity();
			i++;
		}
		t.onLoot(event.getType(), event.getName(), event.getAmount(), ids, quantities, event.getMetadata());
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		final Tracker t = tracker;
		if (t != null)
		{
			t.onWidgetLoaded(event.getGroupId());
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		final Tracker t = tracker;
		if (t != null)
		{
			t.onGameTick();
		}
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		final Tracker t = tracker;
		if (t != null)
		{
			t.onScriptPostFired(event.getScriptId());
		}
	}

	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		final Tracker t = tracker;
		if (t != null)
		{
			final Future<?> write = t.flushForShutdown();
			if (write != null)
			{
				event.waitFor(write);
			}
		}
	}
}
