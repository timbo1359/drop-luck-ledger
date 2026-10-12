package com.dropluckledger.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.dropluckledger.TestData;
import com.dropluckledger.ledger.Ledger;
import com.dropluckledger.ledger.LedgerCodec;
import com.dropluckledger.ledger.LedgerSaver;
import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.client.util.Filepath;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileStoreTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private Filepath dir;

	@Before
	public void setUp() throws IOException
	{
		dir = Filepath.Unchecked.getRooted(tmp.getRoot().toPath()).joinSegment("plugin-data").rooted();
	}

	private File[] files()
	{
		File[] f = new File(tmp.getRoot(), "plugin-data").listFiles();
		return f == null ? new File[0] : f;
	}

	@Test
	public void missingFileReadsAsNull() throws IOException
	{
		assertNull(new FileStore(dir, "ledger-1.json").read());
	}

	@Test
	public void writeCreatesFolderAndReadsBack() throws IOException
	{
		FileStore s = new FileStore(dir, "ledger-1.json");
		s.write("{\"a\":\"ünï\"}");
		assertEquals("{\"a\":\"ünï\"}", s.read());
		assertEquals(1, files().length);
	}

	@Test
	public void overwriteLeavesNoTemporaryFile() throws IOException
	{
		FileStore s = new FileStore(dir, "ledger-1.json");
		s.write("one");
		s.write("two");
		s.write("three");
		assertEquals("three", s.read());
		for (File f : files())
		{
			assertFalse(f.getName(), f.getName().endsWith(".tmp"));
		}
	}

	@Test
	public void keepsTheEarlierSessionsFileOnceAsBackup() throws IOException
	{
		new FileStore(dir, "ledger-1.json").write("last session");
		FileStore s = new FileStore(dir, "ledger-1.json");
		s.write("this session, first save");
		s.write("this session, second save");
		assertEquals("last session", new String(Files.readAllBytes(new File(tmp.getRoot(), "plugin-data/ledger-1.json.bak").toPath()), StandardCharsets.UTF_8));
		assertEquals("this session, second save", s.read());
	}

	@Test
	public void quarantineSetsTheFileAside() throws IOException
	{
		FileStore s = new FileStore(dir, "ledger-1.json");
		s.write("garbage");
		s.quarantine();
		assertNull(s.read());
		boolean found = false;
		for (File f : files())
		{
			found |= f.getName().startsWith("ledger-1.json.unreadable-");
		}
		assertTrue(found);
		s.quarantine(); // nothing left to set aside: no error
	}

	@Test
	public void ledgerNamesPassTheNameChecks() throws IOException
	{
		for (long hash : new long[]{0L, 123456789012345L, -5L, Long.MIN_VALUE, Long.MAX_VALUE})
		{
			FileStore s = new FileStore(dir, FileStore.ledgerName(hash));
			s.write("x");
			assertEquals("x", s.read());
		}
	}

	@Test
	public void cannotLeaveItsFolder() throws IOException
	{
		try
		{
			new FileStore(dir, "../escape.json").write("x");
			fail("a name with a path in it must be refused");
		}
		catch (IllegalArgumentException expected)
		{
			// refused by Filepath
		}
		assertFalse(new File(tmp.getRoot(), "escape.json").exists());
	}

	@Test
	public void refusesAHugeFile() throws IOException
	{
		File big = new File(tmp.getRoot(), "plugin-data");
		assertTrue(big.mkdirs());
		File f = new File(big, "ledger-9.json");
		try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "rw"))
		{
			r.setLength(FileStore.MAX_BYTES + 1);
		}
		try
		{
			new FileStore(dir, "ledger-9.json").read();
			fail("too large");
		}
		catch (IOException expected)
		{
			// refused
		}
	}

	@Test
	public void saverRoundTripThroughTheStore() throws Exception
	{
		ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor();
		try
		{
			Gson gson = new Gson();
			Ledger a = new Ledger(TestData.get());
			LedgerSaver saver = new LedgerSaver(a, gson, ex, 10);
			a.addListener(saver);
			FileStore store = new FileStore(dir, FileStore.ledgerName(42));
			saver.setStore(store);
			a.setKc("zulrah", 321);
			a.logDrop("zulrah", "Tanzanite fang", 300, 1, null, "live");
			saver.flushNow();

			Ledger b = new Ledger(TestData.get());
			LedgerSaver saver2 = new LedgerSaver(b, gson, ex, 10);
			assertTrue(saver2.loadInto(new FileStore(dir, FileStore.ledgerName(42))));
			assertEquals(321, b.kc("zulrah"));
			assertEquals(1, b.copyOf("zulrah").copies("Tanzanite fang"));
			// a different account has its own file
			Ledger c = new Ledger(TestData.get());
			assertTrue(new LedgerSaver(c, gson, ex, 10).loadInto(new FileStore(dir, FileStore.ledgerName(43))));
			assertEquals(0, c.kc("zulrah"));
		}
		finally
		{
			ex.shutdownNow();
		}
	}

	@Test
	public void exportAndImportATextFile() throws IOException
	{
		Filepath f = Filepath.Unchecked.getRooted(new File(tmp.getRoot(), "backup.json").toPath());
		FileStore.writeText(f, "{\"app\":\"x\"}");
		assertEquals("{\"app\":\"x\"}", FileStore.readText(f));
		FileStore.writeText(f, "{}");
		assertEquals("{}", FileStore.readText(f));
	}

	@Test
	public void codecErrorsSurfaceAsFormatException() throws IOException
	{
		try
		{
			LedgerCodec.fromJson(TestData.get(), "not json at all");
			fail();
		}
		catch (LedgerCodec.FormatException expected)
		{
			// as the import code expects
		}
	}
}
