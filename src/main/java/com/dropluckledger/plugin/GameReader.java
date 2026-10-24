package com.dropluckledger.plugin;

import com.dropluckledger.capture.KcMessages;
import com.dropluckledger.plugin.Game.Node;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.gameval.InterfaceID;

/**
 * Reads the game's own records off the screen when the player opens them: the Boss Kill Log and the pages of the collection log.
 * Only reads widgets; the caller decides what to do with the result.  Call on the client thread.
 */
public final class GameReader
{
	private static final Pattern ADVENTURE_LOG_TITLE = Pattern.compile("The Exploits of (.+)");
	/**
	 * A count line of a collection log page: "Zulrah kills: 1,234".
	 */
	private static final Pattern HEADER_COUNT = Pattern.compile("(.+?):\\s*([0-9][0-9,]*)");

	private GameReader()
	{
	}

	/**
	 * One page of the collection log as drawn: its title, the counts under it and the copies of each item the player has.
	 */
	public static final class LogPage
	{
		public final String title;
		/**
		 * The counts under the title as drawn, as the game words them ("Zulrah kills" to 1234).  The game can draw a page with the
		 * previous page's numbers: {@link #settledCounts} gives the ones it holds for the page once it has settled.
		 */
		public final Map<String, Integer> counts;
		/**
		 * Item name to the number of copies the player has (only items the player has).
		 */
		public final Map<String, Integer> items;
		/**
		 * Item name to item id for every item on the page, including the ones the player does not have yet.
		 */
		public final Map<String, Integer> ids;
		/**
		 * Item id to the number drawn for it: the copies, or 0 for an item drawn faded.
		 */
		public final Map<Integer, Integer> drawn;

		LogPage(String title, Map<String, Integer> counts, Map<String, Integer> items, Map<String, Integer> ids, Map<Integer, Integer> drawn)
		{
			this.title = title;
			this.counts = counts;
			this.items = items;
			this.ids = ids;
			this.drawn = drawn;
		}
	}

	/**
	 * The Boss Kill Log as shown on screen: boss name to kill count, 0 included (the server sends the whole log at once, so a 0 is
	 * the game's count too).
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
				if (!boss.isEmpty() && n >= 0)
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
		Map<Integer, Integer> drawn = new LinkedHashMap<>();
		for (Node child : list.children())
		{
			if (child == null || child.itemId() <= 0)
			{
				continue;
			}
			// items the player does not have are drawn faded (opacity above zero)
			int copies = child.opacity() == 0 ? Math.max(1, child.itemQuantity()) : 0;
			drawn.put(child.itemId(), copies);
			String name = game.itemName(child.itemId());
			if (name == null)
			{
				continue;
			}
			ids.putIfAbsent(name, child.itemId());
			if (copies > 0)
			{
				items.merge(name, copies, Integer::sum);
			}
		}
		return new LogPage(title, headerCounts(header), items, ids, drawn);
	}

	/**
	 * The counts in a collection log page's header, below the title: "Zulrah kills: 1,234" gives "Zulrah kills" and 1234, and
	 * "Thermonuclear Smoke Devil kills: 0" gives 0 (a boss with no kills is worth knowing too).  The "Obtained: 3/12" line is not a
	 * count.
	 */
	static Map<String, Integer> headerCounts(Node header)
	{
		Map<String, Integer> out = new LinkedHashMap<>();
		List<Node> lines = header.children();
		for (int i = 1; lines != null && i < lines.size(); i++)
		{
			Node line = lines.get(i);
			if (line == null || line.text() == null)
			{
				continue;
			}
			Matcher m = HEADER_COUNT.matcher(KcMessages.stripTags(line.text()).trim());
			if (!m.matches())
			{
				continue;
			}
			try
			{
				out.merge(m.group(1).trim(), Integer.parseInt(m.group(2).replace(",", "")), Math::max);
			}
			catch (NumberFormatException ex)
			{
				// too large to be a count
			}
		}
		return out;
	}

	/**
	 * The counts of a collection log page as the game holds them once the page has settled.  The game draws a page's counts from three
	 * player variables it reuses for every page ({@code VarPlayerID.COLLECTION_CATEGORY_COUNT} and the next two), and the server can
	 * send a page's values just after the page is drawn, so a page can first show the previous page's numbers under its own labels.
	 * Each count is matched to the variable it was drawn from by the value that variable had when the page was drawn, and takes the
	 * value it has now.  A count that matched no variable, or that matched two that no longer agree, is left out.
	 *
	 * @param shown  label to the number drawn
	 * @param atDraw the variables' values when the page was drawn
	 * @param now    the same variables' values now
	 */
	public static Map<String, Integer> settledCounts(Map<String, Integer> shown, int[] atDraw, int[] now)
	{
		Map<String, Integer> out = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> e : shown.entrySet())
		{
			Integer value = null;
			boolean clash = false;
			for (int i = 0; i < atDraw.length && i < now.length; i++)
			{
				if (e.getValue() != null && atDraw[i] == e.getValue())
				{
					if (value == null)
					{
						value = now[i];
					}
					else if (value != now[i])
					{
						clash = true;
					}
				}
			}
			if (value != null && !clash)
			{
				out.put(e.getKey(), value);
			}
		}
		return out;
	}

	/**
	 * True when the items a page was drawn with are the ones the game holds for it now.  The game draws a page's items from an
	 * inventory the server fills ({@code InventoryID.COLLECTION_TRANSMIT}); a page drawn before the server sent its items shows
	 * another page's numbers.
	 *
	 * @param inventory    item id to how many the inventory holds now, or null when the client has none (then the page can only
	 *                     have been drawn with nothing owned)
	 * @param bodyTypeSwap the player's body type is the one the game draws a few items in another version for
	 *                     ({@code VarbitID.COLLECTION_PLAYER_BODYTYPE}): the version drawn is not the item id the inventory holds,
	 *                     so an owned item drawn under an id the inventory does not hold is not counted against the page
	 */
	public static boolean itemsMatch(LogPage page, Map<Integer, Integer> inventory, boolean bodyTypeSwap)
	{
		Map<Integer, Integer> held = inventory == null ? Collections.<Integer, Integer>emptyMap() : inventory;
		for (Map.Entry<Integer, Integer> e : page.drawn.entrySet())
		{
			Integer n = held.get(e.getKey());
			if (n == null && e.getValue() > 0 && bodyTypeSwap && inventory != null)
			{
				continue;
			}
			if ((n == null ? 0 : n) != e.getValue())
			{
				return false;
			}
		}
		return true;
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
