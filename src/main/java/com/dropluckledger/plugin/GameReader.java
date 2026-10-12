package com.dropluckledger.plugin;

import com.dropluckledger.capture.KcMessages;
import com.dropluckledger.plugin.Game.Node;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.gameval.InterfaceID;

/**
 * Reads the game's own records off the screen when the player opens them: the Boss Kill Log and the pages of the collection
 * log.  Only reads widgets; the caller decides what to do with the result.  Call on the client thread.
 */
public final class GameReader
{
	private static final Pattern ADVENTURE_LOG_TITLE = Pattern.compile("The Exploits of (.+)");

	private GameReader()
	{
	}

	/**
	 * One page of the collection log: its title and the copies of each item the player has.
	 */
	public static final class LogPage
	{
		public final String title;
		/**
		 * Item name to the number of copies the player has (only items the player has).
		 */
		public final Map<String, Integer> items;
		/**
		 * Item name to item id for every item on the page, including the ones the player does not have yet.
		 */
		public final Map<String, Integer> ids;

		LogPage(String title, Map<String, Integer> items, Map<String, Integer> ids)
		{
			this.title = title;
			this.items = items;
			this.ids = ids;
		}
	}

	/**
	 * The Boss Kill Log as shown on screen: boss name to kill count.
	 *
	 * @return null when the Boss Kill Log is not on screen (or not filled in yet)
	 */
	public static Map<String, Integer> killLog(Game game)
	{
		Node title = game.widget(InterfaceID.KillLog.INTERFACE_TITLE);
		Node names = game.widget(InterfaceID.KillLog.NAME);
		Node kills = game.widget(InterfaceID.KillLog.KILL);
		if (title == null || names == null || kills == null || !"Boss Kill Log".equals(title.text()))
		{
			return null;
		}
		List<Node> nameRows = names.children();
		List<Node> killRows = kills.children();
		if (nameRows == null || killRows == null)
		{
			return null;
		}
		Map<String, Integer> out = new LinkedHashMap<>();
		for (int i = 0; i < Math.min(nameRows.size(), killRows.size()); i++)
		{
			Node name = nameRows.get(i);
			Node kill = killRows.get(i);
			if (name == null || kill == null || name.text() == null || kill.text() == null)
			{
				continue;
			}
			String boss = KcMessages.stripTags(name.text()).replace(":", "").trim();
			String count = KcMessages.stripTags(kill.text()).replace(",", "").trim();
			try
			{
				int n = Integer.parseInt(count);
				if (!boss.isEmpty() && n > 0)
				{
					out.put(boss, n);
				}
			}
			catch (NumberFormatException ex)
			{
				// a heading or an empty row
			}
		}
		return out;
	}

	/**
	 * The collection log page on screen: the items the player has, with how many.
	 *
	 * @return null when no page is on screen
	 */
	public static LogPage collectionPage(Game game)
	{
		Node header = game.widget(InterfaceID.Collection.HEADER_TEXT);
		if (header == null || header.children() == null)
		{
			return null;
		}
		Node titleNode = header.child(0);
		if (titleNode == null || titleNode.text() == null)
		{
			return null;
		}
		String title = KcMessages.stripTags(titleNode.text()).trim();
		Node list = game.widget(InterfaceID.Collection.ITEMS_CONTENTS);
		if (title.isEmpty() || list == null || list.children() == null)
		{
			return null;
		}
		Map<String, Integer> items = new LinkedHashMap<>();
		Map<String, Integer> ids = new LinkedHashMap<>();
		for (Node child : list.children())
		{
			if (child == null || child.itemId() <= 0)
			{
				continue;
			}
			String name = game.itemName(child.itemId());
			if (name == null)
			{
				continue;
			}
			ids.putIfAbsent(name, child.itemId());
			// items the player does not have are drawn faded (opacity above zero)
			if (child.opacity() == 0)
			{
				items.merge(name, Math.max(1, child.itemQuantity()), Integer::sum);
			}
		}
		return new LogPage(title, items, ids);
	}

	/**
	 * Whose adventure log is open: the name after "The Exploits of".  The Boss Kill Log opens from the adventure log, and in
	 * a player-owned house it can be another player's.
	 *
	 * @return null when no adventure log is open
	 */
	public static String adventureLogOwner(Game game)
	{
		String owner = ownerIn(game, InterfaceID.MenuNew.TITLE);
		return owner != null ? owner : ownerIn(game, InterfaceID.Menu.LJ_LAYER2);
	}

	private static String ownerIn(Game game, int componentId)
	{
		Node container = game.widget(componentId);
		if (container == null)
		{
			return null;
		}
		String own = owner(container.text());
		if (own != null)
		{
			return own;
		}
		List<Node> children = container.children();
		if (children == null)
		{
			return null;
		}
		for (Node child : children)
		{
			if (child == null)
			{
				continue;
			}
			String found = owner(child.text());
			if (found != null)
			{
				return found;
			}
		}
		return null;
	}

	static String owner(String text)
	{
		if (text == null)
		{
			return null;
		}
		Matcher m = ADVENTURE_LOG_TITLE.matcher(KcMessages.stripTags(text));
		return m.find() ? m.group(1).trim() : null;
	}
}
