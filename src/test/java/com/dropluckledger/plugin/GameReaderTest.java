package com.dropluckledger.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

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
		assertEquals(1, kills.size());
		assertEquals(5, (int) kills.get("Zulrah"));
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
