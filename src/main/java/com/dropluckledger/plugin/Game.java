package com.dropluckledger.plugin;

import java.awt.Component;
import java.awt.image.BufferedImage;
import java.util.List;
import net.runelite.api.Skill;
import net.runelite.client.util.Filepath;

/**
 * Everything the plugin asks of the running game and of RuneLite's services, in one place.
 * <p>
 * {@link RuneLiteGame} is the only class that talks to the real client; the rest of the plugin (and its tests) sees only this
 * interface.  Call the methods on the client thread unless a method says otherwise.
 */
public interface Game
{
	// ------------------------------------------------------------------ the player

	/**
	 * The hash that identifies the logged in account, or -1 when nobody is logged in.
	 */
	long accountHash();

	/**
	 * True while a character is logged in.
	 */
	boolean isLoggedIn();

	/**
	 * True on a normal world.  False on the worlds that keep a save of their own (Leagues, Deadman, ...).
	 */
	boolean isStandardWorld();

	/**
	 * The logged in character's name, or null.
	 */
	String playerName();

	/**
	 * The number of game ticks since the client started; messages and loot of one kill share a tick or are a tick apart.
	 */
	int tickCount();

	/**
	 * The player's unboosted level in a skill.
	 */
	int realLevel(Skill skill);

	/**
	 * The player's experience in a skill.
	 */
	int experience(Skill skill);

	// ------------------------------------------------------------------ the game's screens

	/**
	 * A piece of the game's interface, as far as the plugin reads it.
	 */
	interface Node
	{
		/**
		 * The text shown, or null.
		 */
		String text();

		/**
		 * The pieces inside this one (entries may be null), or null when there is no list.
		 */
		List<Node> children();

		/**
		 * The piece at a position inside this one, or null.
		 */
		Node child(int index);

		/**
		 * The item shown, or -1 (or 0) when none.
		 */
		int itemId();

		int itemQuantity();

		/**
		 * 0 for fully drawn, up to 255 for invisible; the collection log fades the items the player does not have.
		 */
		int opacity();
	}

	/**
	 * The interface component with an id from {@code InterfaceID}, or null when it is not on screen.
	 */
	Node widget(int componentId);

	// ------------------------------------------------------------------ items

	/**
	 * What the game knows about an item.
	 */
	final class ItemInfo
	{
		/**
		 * The game's name for it ("null" for ids that are not items).
		 */
		public final String name;
		/**
		 * True for the noted (bank note) copy of another item.
		 */
		public final boolean noted;
		/**
		 * True for the bank placeholder of another item.
		 */
		public final boolean placeholder;

		public ItemInfo(String name, boolean noted, boolean placeholder)
		{
			this.name = name;
			this.noted = noted;
			this.placeholder = placeholder;
		}
	}

	/**
	 * An item found by searching for a name.
	 */
	final class ItemRef
	{
		public final int id;
		public final String name;

		public ItemRef(int id, String name)
		{
			this.id = id;
			this.name = name;
		}
	}

	/**
	 * What the game knows about an item id, or null when it has nothing for that id.
	 */
	ItemInfo item(int id);

	/**
	 * The game's name for an item, or null when the id is not an item.
	 */
	default String itemName(int id)
	{
		ItemInfo info = item(id);
		return info == null || info.name == null || info.name.isEmpty() || info.name.equals("null") ? null : info.name;
	}

	/**
	 * The id of the plain item behind a noted copy or a placeholder (other ids come back as they are).
	 */
	int canonicalItemId(int id);

	/**
	 * Tradeable items whose names contain the text.
	 */
	List<ItemRef> searchItems(String text);

	/**
	 * The item's picture.  The game fills it in a moment after returning it, and calls {@code whenLoaded} then.
	 * Can be called from any thread.
	 *
	 * @return null when there is no picture
	 */
	BufferedImage itemImage(int id, Runnable whenLoaded);

	// ------------------------------------------------------------------ what other RuneLite plugins saved

	/**
	 * Keys that start with a prefix, among the settings other plugins saved for the logged in profile (empty when there
	 * is no profile).  Can be called from any thread.
	 */
	List<String> savedKeys(String group, String keyPrefix);

	/**
	 * A whole number saved for the logged in profile, or null.  Can be called from any thread.
	 */
	Integer savedInt(String group, String key);

	/**
	 * A text saved for the logged in profile, or null.  Can be called from any thread.
	 */
	String savedText(String group, String key);

	// ------------------------------------------------------------------ chat, threads and files

	/**
	 * Puts a line in the player's chat box (RuneLite colour tags allowed).
	 */
	void gameMessage(String runeLiteFormattedMessage);

	/**
	 * Runs something on the client thread: at once when already there, otherwise as soon as the client thread is free.
	 * Can be called from any thread.
	 */
	void invoke(Runnable task);

	/**
	 * Runs something on the client thread, never at once.  Can be called from any thread.
	 */
	void invokeLater(Runnable task);

	/**
	 * Asks the player where to save a file.  Call on the Swing thread.
	 *
	 * @return the chosen file, or null when the player cancels
	 */
	Filepath chooseFileToSave(Component parent, String title, String fileName, String description, String extension);

	/**
	 * Asks the player which file to open.  Call on the Swing thread.
	 *
	 * @return the chosen file, or null when the player cancels
	 */
	Filepath chooseFileToOpen(Component parent, String title, String description, String extension);
}
