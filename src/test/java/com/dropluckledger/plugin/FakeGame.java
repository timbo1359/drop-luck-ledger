package com.dropluckledger.plugin;

import java.awt.Component;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import net.runelite.api.Skill;
import net.runelite.client.util.Filepath;

/**
 * The game and RuneLite as the plugin sees them, with everything the tests need to set up or look at as plain fields.
 * Work meant for the client thread simply runs at once, on the caller's thread.
 */
public final class FakeGame implements Game
{
	// the player
	public boolean loggedIn;
	public long accountHash = -1L;
	public boolean standardWorld = true;
	public String playerName = "Tester";
	public int tick = 1000;
	public final Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
	public final Map<Integer, Integer> varbits = new HashMap<>();
	public final Map<Integer, Integer> varps = new HashMap<>();
	/**
	 * Inventory id to item id to how many (inventories the client does not have are left out).
	 */
	public final Map<Integer, Map<Integer, Integer>> inventories = new HashMap<>();
	public boolean members = true;
	public boolean lootTracker = true;

	// the screen
	public final Map<Integer, Node> widgets = new HashMap<>();

	// items
	public final Map<Integer, String> names = new HashMap<>();
	/**
	 * Ids that are noted copies of another item (the scan skips them).
	 */
	public final Map<Integer, Integer> notes = new HashMap<>();
	public final List<ItemRef> tradeable = new ArrayList<>();
	public int lookups;
	public int searches;
	/**
	 * What was asked of the picture lookups.
	 */
	public final List<Runnable> pictureCallbacks = new CopyOnWriteArrayList<>();

	// what other RuneLite plugins saved: group -> key -> text
	public boolean hasProfile = true;
	public final Map<String, Map<String, String>> saved = new LinkedHashMap<>();

	// what the plugin did
	public final List<String> chat = new CopyOnWriteArrayList<>();

	// the answers the player gives to the file dialogs
	public Filepath fileToSave;
	public Filepath fileToOpen;

	public void logIn(long hash)
	{
		accountHash = hash;
		loggedIn = true;
	}

	public void logOut()
	{
		accountHash = -1L;
		loggedIn = false;
	}

	public FakeGame addItem(int id, String name)
	{
		names.put(id, name);
		return this;
	}

	public FakeGame addTradeable(int id, String name)
	{
		names.put(id, name);
		tradeable.add(new ItemRef(id, name));
		return this;
	}

	public FakeGame save(String group, String key, String value)
	{
		saved.computeIfAbsent(group, g -> new LinkedHashMap<>()).put(key, value);
		return this;
	}

	// ------------------------------------------------------------------ the player

	@Override
	public long accountHash()
	{
		return accountHash;
	}

	@Override
	public boolean isLoggedIn()
	{
		return loggedIn;
	}

	@Override
	public boolean isStandardWorld()
	{
		return standardWorld;
	}

	@Override
	public String playerName()
	{
		return loggedIn ? playerName : null;
	}

	@Override
	public int tickCount()
	{
		return tick;
	}

	@Override
	public int realLevel(Skill skill)
	{
		return levels.getOrDefault(skill, 1);
	}

	@Override
	public int varbit(int id)
	{
		return varbits.getOrDefault(id, 0);
	}

	@Override
	public int varp(int id)
	{
		return varps.getOrDefault(id, 0);
	}

	@Override
	public Map<Integer, Integer> inventory(int inventoryId)
	{
		Map<Integer, Integer> inv = inventories.get(inventoryId);
		return inv == null ? null : new HashMap<>(inv);
	}

	@Override
	public boolean isMembersWorld()
	{
		return members;
	}

	@Override
	public boolean isLootTrackerOn()
	{
		return lootTracker;
	}

	// ------------------------------------------------------------------ the screen

	@Override
	public Node widget(int componentId)
	{
		return widgets.get(componentId);
	}

	// ------------------------------------------------------------------ items

	@Override
	public ItemInfo item(int id)
	{
		lookups++;
		return new ItemInfo(names.getOrDefault(id, "null"), notes.containsKey(id), false);
	}

	@Override
	public int canonicalItemId(int id)
	{
		return id;
	}

	@Override
	public List<ItemRef> searchItems(String text)
	{
		searches++;
		String q = text.toLowerCase(Locale.ROOT);
		List<ItemRef> out = new ArrayList<>();
		for (ItemRef r : tradeable)
		{
			if (r.name.toLowerCase(Locale.ROOT).contains(q))
			{
				out.add(r);
			}
		}
		return out;
	}

	@Override
	public BufferedImage itemImage(int id, Runnable whenLoaded)
	{
		pictureCallbacks.add(whenLoaded);
		return new BufferedImage(36, 32, BufferedImage.TYPE_INT_ARGB);
	}

	// ------------------------------------------------------------------ what other RuneLite plugins saved

	@Override
	public List<String> savedKeys(String group, String keyPrefix)
	{
		List<String> out = new ArrayList<>();
		if (hasProfile)
		{
			for (String k : saved.getOrDefault(group, new LinkedHashMap<>()).keySet())
			{
				if (k.startsWith(keyPrefix))
				{
					out.add(k);
				}
			}
		}
		return out;
	}

	@Override
	public Integer savedInt(String group, String key)
	{
		String v = savedText(group, key);
		return v == null || v.isEmpty() ? null : Integer.valueOf(v);
	}

	@Override
	public String savedText(String group, String key)
	{
		return hasProfile ? saved.getOrDefault(group, new LinkedHashMap<>()).get(key) : null;
	}

	// ------------------------------------------------------------------ chat, threads and files

	@Override
	public void gameMessage(String runeLiteFormattedMessage)
	{
		chat.add(runeLiteFormattedMessage);
	}

	@Override
	public void invoke(Runnable task)
	{
		task.run();
	}

	@Override
	public void invokeLater(Runnable task)
	{
		task.run();
	}

	@Override
	public Filepath chooseFileToSave(Component parent, String title, String fileName, String description, String extension)
	{
		return fileToSave;
	}

	@Override
	public Filepath chooseFileToOpen(Component parent, String title, String description, String extension)
	{
		return fileToOpen;
	}
}
