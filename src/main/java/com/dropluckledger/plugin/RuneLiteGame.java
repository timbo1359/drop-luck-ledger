package com.dropluckledger.plugin;

import java.awt.Component;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.Filepath;
import net.runelite.http.api.item.ItemPrice;

/**
 * {@link Game} over the real client and RuneLite's services.  This and the plugin class are the only places that use them;
 * everything here is a one-line hand-over.
 */
public final class RuneLiteGame implements Game
{
	private static final String RUNELITE_GROUP = "runelite";
	private static final String LOOT_TRACKER_KEY = "loottrackerplugin";

	private final Client client;
	private final ClientThread clientThread;
	private final ItemManager itemManager;
	private final ConfigManager configManager;
	private final ChatMessageManager chatMessageManager;

	public RuneLiteGame(Client client, ClientThread clientThread, ItemManager itemManager, ConfigManager configManager, ChatMessageManager chatMessageManager)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.itemManager = itemManager;
		this.configManager = configManager;
		this.chatMessageManager = chatMessageManager;
	}

	// ------------------------------------------------------------------ the player

	@Override
	public long accountHash()
	{
		return client.getAccountHash();
	}

	@Override
	public boolean isLoggedIn()
	{
		return client.getGameState() == GameState.LOGGED_IN;
	}

	@Override
	public boolean isStandardWorld()
	{
		return RuneScapeProfileType.getCurrent(client) == RuneScapeProfileType.STANDARD;
	}

	@Override
	public String playerName()
	{
		Player player = client.getLocalPlayer();
		return player == null ? null : player.getName();
	}

	@Override
	public int tickCount()
	{
		return client.getTickCount();
	}

	@Override
	public int realLevel(Skill skill)
	{
		return client.getRealSkillLevel(skill);
	}

	@Override
	public int varbit(int id)
	{
		return client.getVarbitValue(id);
	}

	@Override
	public int varp(int id)
	{
		return client.getVarpValue(id);
	}

	@Override
	public Map<Integer, Integer> inventory(int inventoryId)
	{
		ItemContainer container = client.getItemContainer(inventoryId);
		if (container == null)
		{
			return null;
		}
		Map<Integer, Integer> out = new HashMap<>();
		for (Item item : container.getItems())
		{
			if (item.getId() >= 0 && item.getQuantity() > 0)
			{
				out.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}
		return out;
	}

	@Override
	public boolean isMembersWorld()
	{
		return client.getWorldType().contains(WorldType.MEMBERS);
	}

	@Override
	public boolean isLootTrackerOn()
	{
		// RuneLite keeps whether a plugin is on under its class name in the "runelite" group; unset means its default (on)
		return !"false".equals(configManager.getConfiguration(RUNELITE_GROUP, LOOT_TRACKER_KEY));
	}

	// ------------------------------------------------------------------ the game's screens

	@Override
	public Node widget(int componentId)
	{
		return wrap(client.getWidget(componentId));
	}

	private static Node wrap(Widget widget)
	{
		return widget == null ? null : new WidgetNode(widget);
	}

	private static final class WidgetNode implements Node
	{
		private final Widget widget;

		WidgetNode(Widget widget)
		{
			this.widget = widget;
		}

		@Override
		public String text()
		{
			return widget.getText();
		}

		@Override
		public List<Node> children()
		{
			Widget[] children = widget.getChildren();
			if (children == null)
			{
				return null;
			}
			List<Node> out = new ArrayList<>(children.length);
			for (Widget child : children)
			{
				out.add(wrap(child));
			}
			return out;
		}

		@Override
		public Node child(int index)
		{
			return wrap(widget.getChild(index));
		}

		@Override
		public int itemId()
		{
			return widget.getItemId();
		}

		@Override
		public int itemQuantity()
		{
			return widget.getItemQuantity();
		}

		@Override
		public int opacity()
		{
			return widget.getOpacity();
		}
	}

	// ------------------------------------------------------------------ items

	@Override
	public ItemInfo item(int id)
	{
		try
		{
			ItemComposition c = itemManager.getItemComposition(id);
			return c == null ? null : new ItemInfo(c.getName(), c.getNote() != -1, c.getPlaceholderTemplateId() != -1);
		}
		catch (RuntimeException ex)
		{
			// ids the game has no item for
			return null;
		}
	}

	@Override
	public int canonicalItemId(int id)
	{
		return itemManager.canonicalize(id);
	}

	@Override
	public List<ItemRef> searchItems(String text)
	{
		List<ItemRef> out = new ArrayList<>();
		for (ItemPrice found : itemManager.search(text))
		{
			out.add(new ItemRef(found.getId(), found.getName()));
		}
		return out;
	}

	@Override
	public BufferedImage itemImage(int id, Runnable whenLoaded)
	{
		AsyncBufferedImage image = itemManager.getImage(id);
		if (image != null)
		{
			image.onLoaded(whenLoaded);
		}
		return image;
	}

	// ------------------------------------------------------------------ what other RuneLite plugins saved

	@Override
	public List<String> savedKeys(String group, String keyPrefix)
	{
		String profile = configManager.getRSProfileKey();
		return profile == null ? Collections.emptyList() : configManager.getRSProfileConfigurationKeys(group, profile, keyPrefix);
	}

	@Override
	public Integer savedInt(String group, String key)
	{
		return configManager.getRSProfileConfiguration(group, key, int.class);
	}

	@Override
	public String savedText(String group, String key)
	{
		return configManager.getRSProfileConfiguration(group, key);
	}

	// ------------------------------------------------------------------ chat, threads and files

	@Override
	public void gameMessage(String runeLiteFormattedMessage)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(runeLiteFormattedMessage)
			.build());
	}

	@Override
	public void invoke(Runnable task)
	{
		clientThread.invoke(task);
	}

	@Override
	public void invokeLater(Runnable task)
	{
		clientThread.invokeLater(task);
	}

	@Override
	public Filepath chooseFileToSave(Component parent, String title, String fileName, String description, String extension)
	{
		List<Filepath> files = new Filepath.Chooser()
			.setIsSave()
			.setAcceptsFiles()
			.setDialogTitle(title)
			.setFileName(fileName)
			.addExtensionFilter(description, extension)
			.setDefaultExtension(extension)
			.showDialog(parent);
		return files == null || files.isEmpty() ? null : files.get(0);
	}

	@Override
	public Filepath chooseFileToOpen(Component parent, String title, String description, String extension)
	{
		List<Filepath> files = new Filepath.Chooser()
			.setIsOpen()
			.setAcceptsFiles()
			.setDialogTitle(title)
			.addExtensionFilter(description, extension)
			.showDialog(parent);
		return files == null || files.isEmpty() ? null : files.get(0);
	}
}
