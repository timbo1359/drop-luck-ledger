package com.dropluckledger.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.dropluckledger.TestFiles;
import com.dropluckledger.history.LoggedLoot;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.client.util.Filepath;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DropLogTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private File folder;
	private DropLog log;

	@Before
	public void setUp() throws Exception
	{
		folder = tmp.newFolder("plugin-data");
		Filepath dir = TestFiles.root(folder.toPath()).joinSegment("drop-luck-ledger").rooted();
		log = new DropLog(dir, Runnable::run);
	}

	private static LoggedLoot loot(String boss, int kill, Object... nameQty)
	{
		List<LoggedLoot.Item> items = new java.util.ArrayList<>();
		for (int i = 0; i < nameQty.length; i += 2)
		{
			items.add(new LoggedLoot.Item((String) nameQty[i], (Integer) nameQty[i + 1]));
		}
		return new LoggedLoot(1_000L + kill, boss, kill, items, null);
	}

	private File file(long account)
	{
		return new File(folder, "drop-luck-ledger/" + DropLog.fileName(account));
	}

	@Test
	public void everyLootIsALineAndComesBack() throws Exception
	{
		log.setAccount(7L);
		log.add(loot("zulrah", 12, "Coins", 40_000, "Zulrah's scales", 120));
		Map<String, Integer> snap = new LinkedHashMap<>();
		snap.put("the-whisperer", 30);
		log.add(new LoggedLoot(5_000L, "vardorvis", 44, Collections.singletonList(new LoggedLoot.Item("Virtus mask", 1)), snap));
		log.add(loot("zulrah", 13, "Tanzanite fang", 1));
		List<String> lines = Files.readAllLines(file(7).toPath(), StandardCharsets.UTF_8);
		assertEquals(3, lines.size());
		assertEquals("{\"t\":1012,\"boss\":\"zulrah\",\"kill\":12,\"items\":[[\"Coins\",40000],[\"Zulrah's scales\",120]]}", lines.get(0));
		List<LoggedLoot> z = log.read("zulrah");
		assertEquals(2, z.size());
		assertEquals(12, z.get(0).kill);
		assertEquals("Zulrah's scales", z.get(0).items.get(1).name);
		assertEquals(120, z.get(0).items.get(1).qty);
		assertNull(z.get(0).snap);
		LoggedLoot v = log.read("vardorvis").get(0);
		assertEquals(5_000L, v.t);
		assertEquals(Integer.valueOf(30), v.snap.get("the-whisperer"));
	}

	@Test
	public void eachAccountHasItsOwnLogAndNoneIsWrittenWithoutOne() throws Exception
	{
		log.add(loot("zulrah", 1, "Coins", 1));
		assertFalse(new File(folder, "drop-luck-ledger").exists());
		log.setAccount(1L);
		log.add(loot("zulrah", 5, "Coins", 1));
		log.setAccount(2L);
		log.add(loot("zulrah", 9, "Coins", 1));
		assertEquals(1, log.read("zulrah").size());
		assertEquals(9, log.read("zulrah").get(0).kill);
		log.setAccount(1L);
		assertEquals(5, log.read("zulrah").get(0).kill);
		log.setAccount(null);
		assertTrue(log.read("zulrah").isEmpty());
	}

	@Test
	public void linesThatCannotBeReadAreSkipped() throws Exception
	{
		log.setAccount(3L);
		log.add(loot("zulrah", 1, "Coins", 1));
		Files.write(file(3).toPath(), Arrays.asList("{\"t\":5,\"boss\":\"zulrah\",\"kill\":2,\"ite", "not json", "[1,2]",
			"{\"boss\":\"zulrah\"}", "{\"boss\":\"zulrah\",\"kill\":\"x\",\"items\":[]}"), StandardCharsets.UTF_8,
			java.nio.file.StandardOpenOption.APPEND);
		log.add(loot("zulrah", 3, "Coins", 1));
		List<LoggedLoot> z = log.read("zulrah");
		assertEquals(2, z.size());
		assertEquals(1, z.get(0).kill);
		assertEquals(3, z.get(1).kill);
	}

	@Test
	public void erasingDeletesTheAccountsLog() throws Exception
	{
		log.setAccount(4L);
		log.add(loot("zulrah", 1, "Coins", 1));
		assertTrue(file(4).isFile());
		log.erase();
		assertFalse(file(4).exists());
		assertTrue(log.read("zulrah").isEmpty());
		// a loot without items is not worth a line
		log.add(loot("zulrah", 2));
		assertFalse(file(4).exists());
	}
}
