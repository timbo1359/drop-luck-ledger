package com.dropluckledger.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import net.runelite.api.gameval.InterfaceID;
import org.junit.Before;
import org.junit.Test;

public class GameReaderTest
{
	private FakeGame game;

	@Before
	public void setUp()
	{
		game = new FakeGame().addItem(12922, "Tanzanite fang").addItem(12927, "Magic fang").addItem(13200, "Pet snakeling");
	}

	private void killLog(String title, String[] names, String[] kills)
	{
		game.widgets.put(InterfaceID.KillLog.INTERFACE_TITLE, FakeNode.text(title));
		Game.Node[] n = new Game.Node[names.length];
		Game.Node[] k = new Game.Node[kills.length];
		for (int i = 0; i < names.length; i++)
		{
			n[i] = FakeNode.text(names[i]);
			k[i] = FakeNode.text(kills[i]);
		}
		game.widgets.put(InterfaceID.KillLog.NAME, FakeNode.withChildren(n));
		game.widgets.put(InterfaceID.KillLog.KILL, FakeNode.withChildren(k));
	}

	@Test
	public void readsTheBossKillLog()
	{
		killLog("Boss Kill Log", new String[]{"Zulrah:", "Chambers of Xeric: Challenge Mode", "<col=ff9040>Vorkath"}, new String[]{"1,234", "56", "7"});
		Map<String, Integer> kills = GameReader.killLog(game);
		assertNotNull(kills);
		assertEquals(3, kills.size());
		assertEquals(1234, (int) kills.get("Zulrah"));
		assertEquals(56, (int) kills.get("Chambers of Xeric Challenge Mode"));
		assertEquals(7, (int) kills.get("Vorkath"));
	}

	@Test
	public void ignoresOtherLogsAndRowsThatAreNotCounts()
	{
		killLog("Slayer Kill Log", new String[]{"Zulrah"}, new String[]{"5"});
		assertNull(GameReader.killLog(game));
		killLog("Boss Kill Log", new String[]{"Zulrah", "Header", "Vorkath"}, new String[]{"5", "", "0"});
		Map<String, Integer> kills = GameReader.killLog(game);
		assertEquals(2, kills.size());
		assertEquals(5, (int) kills.get("Zulrah"));
		// a boss with no kills is the game's count too
		assertEquals(0, (int) kills.get("Vorkath"));
	}

	@Test
	public void nothingOnScreenGivesNull()
	{
		assertNull(GameReader.killLog(game));
		assertNull(GameReader.collectionPage(game));
		assertNull(GameReader.adventureLogOwner(game));
	}

	@Test
	public void killLogStillFillingInGivesNull()
	{
		game.widgets.put(InterfaceID.KillLog.INTERFACE_TITLE, FakeNode.text("Boss Kill Log"));
		game.widgets.put(InterfaceID.KillLog.NAME, new FakeNode());
		game.widgets.put(InterfaceID.KillLog.KILL, new FakeNode());
		assertNull(GameReader.killLog(game));
	}

	@Test
	public void readsACollectionLogPage()
	{
		game.widgets.put(InterfaceID.Collection.HEADER_TEXT, FakeNode.withChildren(FakeNode.text("<col=ff9040>Zulrah</col>"), FakeNode.text("3/12")));
		game.widgets.put(InterfaceID.Collection.ITEMS_CONTENTS, FakeNode.withChildren(
			FakeNode.item(12922, 2, true),
			FakeNode.item(12927, 0, false),
			FakeNode.item(13200, 1, true),
			FakeNode.item(99999, 1, true), // unknown to the item list
			FakeNode.item(-1, 0, false)));
		GameReader.LogPage page = GameReader.collectionPage(game);
		assertNotNull(page);
		assertEquals("Zulrah", page.title);
		assertEquals(2, page.items.size());
		assertEquals(2, (int) page.items.get("Tanzanite fang"));
		assertEquals(1, (int) page.items.get("Pet snakeling"));
		// ids of every item on the page, owned or not
		assertEquals(3, page.ids.size());
		assertEquals(12927, (int) page.ids.get("Magic fang"));
		// what was drawn for each item, by id, the faded ones as 0
		assertEquals(4, page.drawn.size());
		assertEquals(2, (int) page.drawn.get(12922));
		assertEquals(0, (int) page.drawn.get(12927));
		assertEquals(1, (int) page.drawn.get(99999));
	}

	@Test
	public void readsTheCountsUnderACollectionLogPagesTitle()
	{
		game.widgets.put(InterfaceID.Collection.HEADER_TEXT, FakeNode.withChildren(
			FakeNode.text("<col=ff9040>Callisto and Artio</col>"),
			FakeNode.text("Obtained: <col=ffff00>3/12</col>"),
			FakeNode.text("Callisto kills: <col=ffffff>1,234</col>"),
			null,
			FakeNode.text("Artio kills: <col=ffffff>56</col>"),
			FakeNode.text("Something: not a number"),
			FakeNode.text("Personal Best: 1:23.40"),
			FakeNode.text("Zero kills: 0")));
		game.widgets.put(InterfaceID.Collection.ITEMS_CONTENTS, FakeNode.withChildren(FakeNode.item(12922, 1, true)));
		GameReader.LogPage page = GameReader.collectionPage(game);
		assertEquals("Callisto and Artio", page.title);
		assertEquals(3, page.counts.size());
		assertEquals(1234, (int) page.counts.get("Callisto kills"));
		assertEquals(56, (int) page.counts.get("Artio kills"));
		// a boss with no kills is worth knowing too
		assertEquals(0, (int) page.counts.get("Zero kills"));
		assertEquals(1, page.items.size());
		// a page with only its title has no counts
		game.widgets.put(InterfaceID.Collection.HEADER_TEXT, FakeNode.withChildren(FakeNode.text("Zulrah")));
		assertTrue(GameReader.collectionPage(game).counts.isEmpty());
	}

	@Test
	public void countsDrawnBeforeThePagesNumbersCameAreTakenFromTheirVariablesAsTheyAreNow()
	{
		// the Skotizo page drawn with the previous page's 117, then the server sends Skotizo's 5
		assertEquals(counts("Skotizo kills", 5), GameReader.settledCounts(counts("Skotizo kills", 117), new int[]{117, 3, 0}, new int[]{5, 3, 0}));
		// two lines, each from its own variable
		assertEquals(counts("Artio kills", 30, "Callisto kills", 20),
			GameReader.settledCounts(counts("Artio kills", 7, "Callisto kills", 3), new int[]{7, 3, 0}, new int[]{30, 20, 0}));
		// numbers that had already come stay as they are
		assertEquals(counts("Zulrah kills", 1500), GameReader.settledCounts(counts("Zulrah kills", 1500), new int[]{1500, 0, 0}, new int[]{1500, 0, 0}));
		// a 0 two variables had, which no longer agree: not known which it was drawn from, so left out
		assertTrue(GameReader.settledCounts(counts("Thermonuclear Smoke Devil kills", 0), new int[]{0, 0, 9}, new int[]{5, 0, 9}).isEmpty());
		// the same 0 in both, still agreeing
		assertEquals(counts("Thermonuclear Smoke Devil kills", 0),
			GameReader.settledCounts(counts("Thermonuclear Smoke Devil kills", 0), new int[]{0, 0, 9}, new int[]{0, 0, 9}));
		// a number no variable had is not a count of this page
		assertTrue(GameReader.settledCounts(counts("Zulrah kills", 42), new int[]{1, 2, 3}, new int[]{1, 2, 3}).isEmpty());
	}

	private static Map<String, Integer> counts(Object... kv)
	{
		Map<String, Integer> m = new java.util.LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2)
		{
			m.put((String) kv[i], (Integer) kv[i + 1]);
		}
		return m;
	}

	@Test
	public void theItemsDrawnAreCheckedAgainstTheInventoryTheGameDrawsThemFrom()
	{
		game.widgets.put(InterfaceID.Collection.HEADER_TEXT, FakeNode.withChildren(FakeNode.text("Zulrah")));
		game.widgets.put(InterfaceID.Collection.ITEMS_CONTENTS, FakeNode.withChildren(
			FakeNode.item(12922, 2, true),
			FakeNode.item(12927, 0, false),
			FakeNode.item(13200, 1, true)));
		GameReader.LogPage page = GameReader.collectionPage(game);
		assertTrue(GameReader.itemsMatch(page, inventory(12922, 2, 13200, 1), false));
		// items of other pages in the inventory do not matter
		assertTrue(GameReader.itemsMatch(page, inventory(12922, 2, 13200, 1, 4151, 3), false));
		// drawn before the server sent the page's items: the numbers are another page's
		assertFalse(GameReader.itemsMatch(page, inventory(12922, 3, 13200, 1), false));
		assertFalse(GameReader.itemsMatch(page, inventory(12922, 2, 13200, 1, 12927, 1), false));
		assertFalse(GameReader.itemsMatch(page, inventory(), false));
		assertFalse(GameReader.itemsMatch(page, null, false));
		assertFalse(GameReader.itemsMatch(page, null, true));
	}

	@Test
	public void aPageWithNothingOwnedMatchesBeforeTheGameHasSentAnyItems()
	{
		game.widgets.put(InterfaceID.Collection.HEADER_TEXT, FakeNode.withChildren(FakeNode.text("Zulrah")));
		game.widgets.put(InterfaceID.Collection.ITEMS_CONTENTS, FakeNode.withChildren(
			FakeNode.item(12922, 0, false),
			FakeNode.item(13200, 0, false)));
		GameReader.LogPage page = GameReader.collectionPage(game);
		assertTrue(GameReader.itemsMatch(page, null, false));
		assertTrue(GameReader.itemsMatch(page, inventory(), false));
		assertFalse(GameReader.itemsMatch(page, inventory(13200, 1), false));
	}

	@Test
	public void anItemDrawnInTheOtherBodyTypesVersionIsNotHeldAgainstThePage()
	{
		// the game looks the count up under the item's own id, then draws the version for the player's body type
		game.widgets.put(InterfaceID.Collection.HEADER_TEXT, FakeNode.withChildren(FakeNode.text("Random Events")));
		game.widgets.put(InterfaceID.Collection.ITEMS_CONTENTS, FakeNode.withChildren(
			FakeNode.item(6186, 1, true),
			FakeNode.item(12922, 2, true),
			FakeNode.item(13200, 0, false)));
		GameReader.LogPage page = GameReader.collectionPage(game);
		Map<Integer, Integer> held = inventory(6184, 1, 12922, 2);
		assertFalse(GameReader.itemsMatch(page, held, false));
		assertTrue(GameReader.itemsMatch(page, held, true));
		// the other items are still checked
		assertFalse(GameReader.itemsMatch(page, inventory(6184, 1, 12922, 3), true));
		assertFalse(GameReader.itemsMatch(page, inventory(6184, 1, 12922, 2, 13200, 1), true));
	}

	private static Map<Integer, Integer> inventory(int... idsAndCounts)
	{
		Map<Integer, Integer> m = new java.util.HashMap<>();
		for (int i = 0; i < idsAndCounts.length; i += 2)
		{
			m.put(idsAndCounts[i], idsAndCounts[i + 1]);
		}
		return m;
	}

	@Test
	public void ownedItemWithNoQuantityCountsAsOne()
	{
		game.widgets.put(InterfaceID.Collection.HEADER_TEXT, FakeNode.withChildren(FakeNode.text("Zulrah")));
		game.widgets.put(InterfaceID.Collection.ITEMS_CONTENTS, FakeNode.withChildren(FakeNode.item(12922, 0, true)));
		assertEquals(1, (int) GameReader.collectionPage(game).items.get("Tanzanite fang"));
	}

	@Test
	public void adventureLogOwnerFromEitherInterface()
	{
		game.widgets.put(InterfaceID.MenuNew.TITLE, FakeNode.withChildren(null, FakeNode.text("<col=ffffff>The Exploits of Someone Else</col>")));
		assertEquals("Someone Else", GameReader.adventureLogOwner(game));
		game.widgets.clear();
		game.widgets.put(InterfaceID.Menu.LJ_LAYER2, FakeNode.text("The Exploits of Tester"));
		assertEquals("Tester", GameReader.adventureLogOwner(game));
		game.widgets.clear();
		game.widgets.put(InterfaceID.MenuNew.TITLE, FakeNode.text("Quest List"));
		assertNull(GameReader.adventureLogOwner(game));
	}
}
