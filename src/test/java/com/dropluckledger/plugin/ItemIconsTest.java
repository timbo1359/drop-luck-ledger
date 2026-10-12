package com.dropluckledger.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class ItemIconsTest
{
	private FakeGame game;
	private ItemIcons icons;

	@Before
	public void setUp()
	{
		game = new FakeGame();
		icons = new ItemIcons(game);
	}

	@Test
	public void learnedIdsAreFoundIgnoringCaseAndPunctuation()
	{
		icons.learn("Pet snakeling", 12921);
		assertEquals(12921, icons.id("Pet Snakeling"));
		assertEquals(12921, icons.id("pet snakeling!"));
		assertTrue(icons.isDirty());
	}

	@Test
	public void searchesTradeableItemsForAnExactName()
	{
		game.addTradeable(12922, "Tanzanite fang").addTradeable(12923, "Tanzanite fang (uncharged)").addTradeable(555, "Tanzanite mutagen");
		assertEquals(12922, icons.id("tanzanite FANG"));
		assertEquals(0, icons.id("Tanzanite"));
		assertNotNull(icons.image("Tanzanite fang", () -> { }));
		assertNull(icons.image("Not an item", () -> { }));
	}

	@Test
	public void asksTheSearchOnlyOncePerName()
	{
		for (int i = 0; i < 5; i++)
		{
			assertEquals(0, icons.id("Pet Zilyana"));
		}
		assertEquals(1, game.searches);
	}

	@Test
	public void snapshotAndRestoreRoundTrip()
	{
		icons.learn("Vorki", 21992);
		icons.learn("Dragonbone necklace", 22111);
		Map<String, Integer> saved = icons.snapshot();
		assertFalse(icons.isDirty());
		assertEquals(2, saved.size());
		ItemIcons again = new ItemIcons(new FakeGame());
		again.restore(saved, 777L);
		assertEquals(21992, again.id("Vorki"));
		assertEquals(777L, again.scannedAt());
		assertFalse(again.isDirty());
		Map<String, Integer> junk = new HashMap<>();
		junk.put("bad", -3);
		junk.put(null, 4);
		again.restore(junk, 1L);
		assertEquals(0, again.id("bad"));
	}

	@Test
	public void scanFindsPetsAndStopsWhenEverythingIsFound()
	{
		game.addItem(10, "Cabbage").addItem(250, "Vorki").addItem(251, "Vorki").addItem(900, "Pet snakeling");
		game.notes.put(249, 5); // a noted copy of Vorki before the real one: skipped
		game.names.put(249, "Vorki");
		icons.startScan(Arrays.asList("Vorki", "Pet snakeling", "Nothing here"));
		assertTrue(icons.isScanning());
		int ticks = 0;
		boolean done = false;
		while (!done)
		{
			done = icons.scanTick(5000L + ticks);
			ticks++;
			assertTrue("scan should end", ticks < 1000);
		}
		assertFalse(icons.isScanning());
		assertEquals(250, icons.id("Vorki")); // not the noted 249, not the later 251
		assertEquals(900, icons.id("Pet snakeling"));
		assertEquals(0, icons.id("Nothing here"));
		assertTrue(icons.scannedAt() >= 5000L);
		// 100 ids per tick, up to the limit, because one name never turns up
		assertEquals(ItemIcons.SCAN_LIMIT / ItemIcons.SCAN_PER_TICK, ticks);
	}

	@Test
	public void scanStopsEarlyWhenAllNamesAreFound()
	{
		game.addItem(30, "Vorki");
		icons.startScan(Arrays.asList("Vorki"));
		assertTrue(icons.scanTick(1L));
		assertEquals(30, icons.id("Vorki"));
		assertEquals(1L, icons.scannedAt());
	}

	@Test
	public void scanOfNamesAlreadyKnownDoesNothing()
	{
		icons.learn("Vorki", 30);
		icons.startScan(Arrays.asList("Vorki"));
		assertFalse(icons.isScanning());
		assertFalse(icons.scanTick(1L));
	}

	@Test
	public void secondStartWhileScanningIsIgnored()
	{
		icons.startScan(Arrays.asList("A thing"));
		icons.scanTick(1L);
		icons.startScan(Arrays.asList("Another thing"));
		assertTrue(icons.isScanning());
	}
}
