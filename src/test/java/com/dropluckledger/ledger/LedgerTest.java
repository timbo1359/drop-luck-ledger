package com.dropluckledger.ledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.dropluckledger.TestData;
import com.dropluckledger.engine.Data;
import com.dropluckledger.engine.Luck;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class LedgerTest
{
	private final Data data = TestData.get();
	private final Gson gson = new Gson();

	private Ledger ledger()
	{
		return new Ledger(data, () -> 1_700_000_000_000L);
	}

	@Test
	public void killCountNeverDropsBelowAKnownDropKill()
	{
		Ledger l = ledger();
		l.logDrop("zulrah", "Magic fang", 300, 1, null, Drop.SRC_MANUAL);
		assertEquals(300, l.kc("zulrah"));
		l.setKc("zulrah", 100);
		assertEquals(300, l.kc("zulrah"));
		l.setKc("zulrah", 450);
		assertEquals(450, l.kc("zulrah"));
		l.addKc("zulrah", -1000);
		assertEquals(300, l.kc("zulrah"));
	}

	@Test
	public void editingTheKillCountByHandForgetsTheGamesCountsSeenSoFar()
	{
		Ledger l = ledger();
		l.update("zulrah", s ->
		{
			s.kc = 100;
			s.labels().put("Zulrah", 100);
			return null;
		});
		l.addKc("zulrah", 10);
		assertTrue(l.copyOf("zulrah").labels().isEmpty());
		l.update("zulrah", s ->
		{
			s.labels().put("Zulrah", 110);
			return null;
		});
		l.setKc("zulrah", 120);
		assertTrue(l.copyOf("zulrah").labels().isEmpty());
	}

	@Test
	public void theGamesWholeCountSetsTheKillCountEitherWayButNotBelowADropsKill()
	{
		Ledger l = ledger();
		Map<String, Integer> game = new LinkedHashMap<>();
		game.put("Zulrah", 150);
		l.setKc("zulrah", 100);
		assertTrue(l.kcToGame("zulrah", game, 150));
		assertEquals(150, l.kc("zulrah"));
		assertEquals(Integer.valueOf(150), l.copyOf("zulrah").labels().get("Zulrah"));
		assertFalse(l.kcToGame("zulrah", game, 150));
		game.put("Zulrah", 5);
		assertTrue(l.kcToGame("zulrah", game, 5));
		assertEquals(5, l.kc("zulrah"));
		l.logDrop("zulrah", "Magic fang", 40, 1, null, Drop.SRC_MANUAL);
		game.put("Zulrah", 3);
		l.kcToGame("zulrah", game, 3);
		assertEquals(40, l.kc("zulrah"));
		assertEquals(Integer.valueOf(3), l.copyOf("zulrah").labels().get("Zulrah"));
	}

	@Test
	public void newKillsOnlyStartsAPeriodAtTheNextKill()
	{
		Ledger l = ledger();
		l.setKc("zulrah", 100);
		l.setParam("zulrah", "ca", 1, true);
		BossState z = l.copyOf("zulrah");
		assertEquals(2, z.segs.size());
		assertEquals(101, z.segs.get(1).from);
		assertEquals(1, z.segs.get(1).params.get("ca"));
		assertNull(z.segs.get(0).params.get("ca"));
		// a second change at the same kill count reuses the period
		l.setParam("zulrah", "konar", 1, true);
		z = l.copyOf("zulrah");
		assertEquals(2, z.segs.size());
		assertEquals(1, z.segs.get(1).params.get("konar"));
		assertEquals(1, z.segs.get(1).params.get("ca"));
		// "all kills" changes every period
		l.setParam("zulrah", "ca", 0, false);
		z = l.copyOf("zulrah");
		assertEquals(0, z.segs.get(0).params.get("ca"));
		assertEquals(0, z.segs.get(1).params.get("ca"));
		l.collapsePeriods("zulrah");
		z = l.copyOf("zulrah");
		assertEquals(1, z.segs.size());
		assertEquals(1, z.segs.get(0).params.get("konar"));
	}

	@Test
	public void trackedItemsFollowTheDefaultsHidingAndAdding()
	{
		Ledger l = ledger();
		List<String> def = l.trackedNames("zulrah");
		assertTrue(def.contains("Tanzanite fang"));
		l.setTracked("zulrah", "Tanzanite fang", false);
		assertFalse(l.trackedNames("zulrah").contains("Tanzanite fang"));
		assertEquals(java.util.Collections.singletonList("Tanzanite fang"), l.copyOf("zulrah").hide);
		l.setTracked("zulrah", "Tanzanite fang", true);
		assertTrue(l.trackedNames("zulrah").contains("Tanzanite fang"));
		assertTrue(l.copyOf("zulrah").hide.isEmpty());
		// an item outside the defaults is added to "track" and shown last
		assertFalse(def.contains("Zulrah's scales"));
		l.setTracked("zulrah", "Zulrah's scales", true);
		List<String> t = l.trackedNames("zulrah");
		assertEquals("Zulrah's scales", t.get(t.size() - 1));
		l.setTracked("zulrah", "Zulrah's scales", false);
		assertFalse(l.trackedNames("zulrah").contains("Zulrah's scales"));
		// logging a hidden item shows it again
		l.setTracked("zulrah", "Magic fang", false);
		l.logDrop("zulrah", "Magic fang", 5, 1, null, Drop.SRC_MANUAL);
		assertTrue(l.trackedNames("zulrah").contains("Magic fang"));
	}

	@Test
	public void recordGivesTheMathsACopy()
	{
		Ledger l = ledger();
		l.setKc("zulrah", 40);
		l.logDrop("zulrah", "Magic fang", 33, 1, null, Drop.SRC_MANUAL);
		l.addOwned("zulrah", "Tanzanite fang", 2);
		Luck.BossRecord r = l.record("zulrah");
		assertEquals(40, r.kc);
		assertEquals(3, r.drops.size());
		assertEquals(Integer.valueOf(33), r.drops.get(0).kc);
		assertNull(r.drops.get(1).kc);
		assertEquals(1, r.segs.size());
		// the record is a copy
		l.logDrop("zulrah", "Magic fang", 35, 1, null, Drop.SRC_MANUAL);
		assertEquals(3, r.drops.size());
		Luck.Model m = Luck.makeModel(data.boss("zulrah"), r, l.globals());
		assertNotNull(m);
		Luck.ItemAnalysis a = Luck.analyse(m, "Magic fang", 50);
		assertEquals(1, a.O);
	}

	@Test
	public void extraCopiesGoInTheOrderTheyAreLikeliestToBeWrong()
	{
		long[] now = {1000};
		Ledger l = new Ledger(data, () -> now[0]);
		String[] order = {"live 10", "live 20", "live 20 again", "live 30", "manual 25", "import"};
		for (String what : order)
		{
			now[0] += 1000;
			if (what.startsWith("import"))
			{
				l.addOwned("zulrah", "Tanzanite fang", 1);
			}
			else
			{
				int kc = Integer.parseInt(what.replaceAll("[^0-9]", ""));
				l.logDrop("zulrah", "Tanzanite fang", kc, 1, null, what.startsWith("manual") ? Drop.SRC_MANUAL : Drop.SRC_LIVE);
			}
		}
		l.logDrop("zulrah", "Magic fang", 20, 1, null, Drop.SRC_LIVE);
		l.update("zulrah", s ->
		{
			s.provDrops = new ArrayList<>();
			for (Drop d : s.drops)
			{
				s.provDrops.add(d.id);
			}
			return null;
		});
		// only the imported copy when asked for those alone
		assertEquals(1, l.removeExtra("zulrah", "Tanzanite fang", 3, true));
		assertEquals(5, l.copies("zulrah", "Tanzanite fang"));
		// then the one typed in by hand, then one of the two at kill 20 (the later), then the newest
		assertEquals(3, l.removeExtra("zulrah", "Tanzanite fang", 3, false));
		BossState z = l.copyOf("zulrah");
		List<Integer> left = new ArrayList<>();
		for (Drop d : z.drops)
		{
			if (d.item.equals("Tanzanite fang"))
			{
				left.add(d.kc);
				assertEquals(Drop.SRC_LIVE, d.src);
			}
		}
		assertEquals(java.util.Arrays.asList(10, 20), left);
		assertEquals(3000, find(z, "Tanzanite fang", 20).t);
		// other items stay, and what went is no longer a provisional drop
		assertEquals(1, z.copies("Magic fang"));
		assertEquals(3, z.provDrops.size());
		// never more than there are
		assertEquals(2, l.removeExtra("zulrah", "Tanzanite fang", 5, false));
		assertEquals(0, l.removeExtra("zulrah", "Tanzanite fang", 1, false));
		assertEquals(0, l.removeExtra("zulrah", "Tanzanite fang", 0, false));
	}

	private static Drop find(BossState s, String item, int kc)
	{
		for (Drop d : s.drops)
		{
			if (d.item.equals(item) && d.kc != null && d.kc == kc)
			{
				return d;
			}
		}
		throw new AssertionError("no " + item + " at " + kc);
	}

	@Test
	public void changesTellTheListeners()
	{
		Ledger l = ledger();
		List<String> seen = new ArrayList<>();
		l.addListener(seen::add);
		long r0 = l.revision("zulrah");
		l.setKc("zulrah", 5);
		l.logDrop("zulrah", "Magic fang", 5, 1, null, null);
		l.updatePrefs(p -> p.window = 25);
		assertEquals(3, seen.size());
		assertEquals("zulrah", seen.get(0));
		assertNull(seen.get(2));
		assertTrue(l.revision("zulrah") > r0);
		assertEquals(25, l.prefs().window);
		l.updatePrefs(p -> p.window = 99999);
		assertEquals(5000, l.prefs().window);
	}

	@Test
	public void roundTripKeepsEverything()
	{
		Ledger l = ledger();
		l.setKc("zulrah", 123);
		l.setParam("zulrah", "ca", 1, true);
		Map<String, Integer> snap = new LinkedHashMap<>();
		snap.put("callisto", 40);
		l.logDrop("venenatis", "Dragon pickaxe", 12, 1, snap, Drop.SRC_LIVE);
		l.addOwned("zulrah", "Tanzanite fang", 2);
		l.update("zulrah", s ->
		{
			s.labels().put("Zulrah", 123);
			s.prov = 2;
			s.provDrops = new ArrayList<>(java.util.Arrays.asList("abc"));
			return null;
		});
		l.updatePrefs(p ->
		{
			p.rowi = 1;
			p.window = 30;
			p.sel = "zulrah";
		});
		String json = LedgerCodec.toJson(gson, l);
		Ledger back;
		try
		{
			back = LedgerCodec.fromJson(data, json);
		}
		catch (LedgerCodec.FormatException ex)
		{
			throw new AssertionError(ex);
		}
		assertEquals(json.replaceAll("\"saved\":\"[^\"]*\"", ""), LedgerCodec.toJson(gson, back).replaceAll("\"saved\":\"[^\"]*\"", ""));
		BossState z = back.copyOf("zulrah");
		assertEquals(123, z.kc);
		assertEquals(2, z.segs.size());
		assertEquals(Integer.valueOf(2), z.prov);
		assertEquals(Integer.valueOf(123), z.labelKc.get("Zulrah"));
		assertEquals(2, z.copies("Tanzanite fang"));
		assertEquals(Drop.SRC_IMPORT, z.drops.get(0).src);
		BossState v = back.copyOf("venenatis");
		assertEquals(Integer.valueOf(40), v.drops.get(0).snap.get("callisto"));
		assertEquals(Drop.SRC_LIVE, v.drops.get(0).src);
		assertEquals(1, back.prefs().rowi);
		assertEquals(30, back.prefs().window);
		assertEquals("zulrah", back.prefs().sel);
	}

	@Test
	public void theGameHistoryAnEarlierVersionKeptIsLetGo() throws Exception
	{
		String json = "{\"app\":\"drop-luck-ledger\",\"v\":1,\"prefs\":{},\"bosses\":{\"zulrah\":{\"kc\":5}},"
			+ "\"history\":{\"kc\":{\"Zulrah\":700},\"log\":{\"Zulrah\":{\"Tanzanite fang\":1}},\"kcTime\":1,\"logTime\":2}}";
		Ledger l = LedgerCodec.fromJson(data, json);
		assertEquals(5, l.kc("zulrah"));
		assertFalse(LedgerCodec.toJson(gson, l).contains("history"));
	}

	@Test
	public void readsABackupFromTheWebApp()
	{
		String json = "{\"app\":\"drop-luck-ledger\",\"v\":1,\"saved\":\"2026-10-01T10:00:00.000Z\","
			+ "\"prefs\":{\"window\":25,\"rowi\":1,\"lq\":0,\"theme\":\"dark\",\"scope\":\"new\",\"sel\":\"vorkath\",\"tab\":\"sim\",\"images\":1},"
			+ "\"bosses\":{"
			+ "\"zulrah\":{\"kc\":5,\"segs\":[{\"from\":1,\"params\":{\"konar\":1}}],"
			+ "\"drops\":[{\"id\":\"a1\",\"item\":\"Magic fang\",\"kc\":300,\"qty\":1,\"t\":1700000000000},"
			+ "{\"id\":\"a2\",\"item\":\"Tanzanite fang\",\"kc\":null,\"qty\":1,\"t\":0,\"src\":\"import\"}],\"track\":[],\"hide\":[\"Uncut onyx\"],\"u\":1700000000001},"
			+ "\"nex\":{\"kc\":70,\"segs\":[{\"from\":5,\"params\":{\"mvp\":true,\"team\":\"4\",\"bogus\":3}}],\"drops\":[],\"track\":[],\"hide\":[]},"
			+ "\"not-a-boss\":{\"kc\":9,\"segs\":[],\"drops\":[],\"track\":[],\"hide\":[]},"
			+ "\"vorkath\":{\"kc\":\"12\",\"segs\":[],\"drops\":[{\"item\":7},{\"id\":\"v1\",\"item\":\"Vorki\",\"kc\":0,\"qty\":0}],\"track\":[\"Dragon bones\",5],\"hide\":null}"
			+ "}}";
		Ledger l;
		try
		{
			l = LedgerCodec.fromJson(data, json);
		}
		catch (LedgerCodec.FormatException ex)
		{
			throw new AssertionError(ex);
		}
		assertEquals(25, l.prefs().window);
		assertEquals(1, l.prefs().rowi);
		assertEquals(0, l.prefs().lq);
		assertEquals("new", l.prefs().scope);
		assertEquals("vorkath", l.prefs().sel);
		BossState z = l.copyOf("zulrah");
		assertEquals(300, z.kc); // raised to the latest drop
		assertEquals(1, z.segs.get(0).params.get("konar"));
		assertNull(z.drops.get(1).kc);
		assertEquals(Drop.SRC_IMPORT, z.drops.get(1).src);
		assertEquals(java.util.Collections.singletonList("Uncut onyx"), z.hide);
		BossState c = l.copyOf("nex");
		assertEquals(2, c.segs.size()); // the first period always starts at kill 1
		assertEquals(1, c.segs.get(0).from);
		assertEquals(5, c.segs.get(1).from);
		assertEquals(1, c.segs.get(1).params.get("mvp"));
		assertEquals(4, c.segs.get(1).params.get("team"));
		assertFalse(c.segs.get(1).params.containsKey("bogus"));
		assertFalse(l.activeBosses().contains("not-a-boss"));
		BossState v = l.copyOf("vorkath");
		assertEquals(12, v.kc);
		assertEquals(1, v.drops.size()); // the entry without a name is dropped
		assertEquals(Integer.valueOf(1), v.drops.get(0).kc);
		assertEquals(1, v.drops.get(0).qty);
		assertEquals(java.util.Collections.singletonList("Dragon bones"), v.track);
		assertTrue(v.hide.isEmpty());
	}

	@Test
	public void bossesThePluginLeavesOutAreKeptInTheFile() throws Exception
	{
		String titans = "{\"kc\":70,\"segs\":[{\"from\":1,\"params\":{}}],"
			+ "\"drops\":[{\"id\":\"t1\",\"item\":\"Giantsoul amulet\",\"kc\":64,\"qty\":1,\"t\":5}],\"track\":[],\"hide\":[]}";
		String json = "{\"app\":\"drop-luck-ledger\",\"v\":1,\"prefs\":{},\"bosses\":{\"royal-titans\":" + titans + ",\"zulrah\":{\"kc\":5}}}";
		Ledger l = LedgerCodec.fromJson(data, json);
		assertEquals(java.util.Collections.singletonList("zulrah"), l.activeBosses());
		// written back as it was, so the web app still has it
		com.google.gson.JsonObject saved = new com.google.gson.JsonParser().parse(LedgerCodec.toJson(gson, l)).getAsJsonObject().getAsJsonObject("bosses");
		assertEquals(new com.google.gson.JsonParser().parse(titans), saved.get("royal-titans"));
		assertTrue(saved.has("zulrah"));
		// a restore keeps it, a merge brings it over when it is missing
		Ledger restored = ledger();
		restored.replaceWith(l);
		assertTrue(LedgerCodec.toJson(gson, restored).contains("Giantsoul amulet"));
		Ledger merged = ledger();
		merged.setKc("vorkath", 3);
		merged.mergeFrom(l);
		assertTrue(LedgerCodec.toJson(gson, merged).contains("Giantsoul amulet"));
		assertEquals(3, merged.kc("vorkath"));
		// erasing everything (a fresh ledger) lets it go
		restored.replaceWith(ledger());
		assertFalse(LedgerCodec.toJson(gson, restored).contains("royal-titans"));
	}

	@Test
	public void raidsKeptByTheVersionThatLeftThemOutAreBack() throws Exception
	{
		// the version without raids wrote them back untouched; this one reads them as bosses again
		String cox = "{\"kc\":70,\"segs\":[{\"from\":1,\"params\":{\"cm\":1}}],"
			+ "\"drops\":[{\"id\":\"t1\",\"item\":\"Twisted bow\",\"kc\":64,\"qty\":1,\"t\":5}],\"track\":[],\"hide\":[]}";
		String json = "{\"app\":\"drop-luck-ledger\",\"v\":1,\"prefs\":{},\"bosses\":{\"chambers-of-xeric\":" + cox + "}}";
		Ledger l = LedgerCodec.fromJson(data, json);
		assertTrue(l.activeBosses().contains("chambers-of-xeric"));
		BossState c = l.copyOf("chambers-of-xeric");
		assertEquals(70, c.kc);
		assertEquals(Integer.valueOf(64), c.drops.get(0).kc);
		assertEquals(1, ((Number) c.paramsAt(10).get("cm")).intValue());
	}

	@Test
	public void theBossesToShowAreSavedWithTheLedger() throws Exception
	{
		Ledger l = ledger();
		l.setKc("zulrah", 5);
		long zulrah = l.revision("zulrah");
		List<String> told = new ArrayList<>();
		l.addListener(told::add);
		l.setShown(java.util.Arrays.asList("zulrah", "vorkath"), false);
		assertFalse(l.shown("zulrah"));
		assertFalse(l.shown("vorkath"));
		assertTrue(l.shown("duke-sucellus"));
		assertEquals(1, told.size());
		assertEquals(zulrah, l.revision("zulrah")); // no figure depends on it: what was worked out stays
		l.setShown(java.util.Collections.singletonList("vorkath"), false);
		assertEquals(1, told.size()); // nothing changed, nothing told
		Ledger back = LedgerCodec.fromJson(data, LedgerCodec.toJson(gson, l));
		assertEquals(java.util.Arrays.asList("zulrah", "vorkath"), back.prefs().hiddenBosses);
		assertEquals(5, back.kc("zulrah")); // still recorded
		l.setShown(java.util.Collections.singletonList("zulrah"), true);
		assertTrue(l.shown("zulrah"));
		// unknown bosses and repeats are dropped when read
		Ledger odd = LedgerCodec.fromJson(data, "{\"app\":\"drop-luck-ledger\",\"v\":1,\"prefs\":{\"hiddenBosses\":[\"zulrah\",\"not-a-boss\",\"zulrah\",5]}}");
		assertEquals(java.util.Collections.singletonList("zulrah"), odd.prefs().hiddenBosses);
		// a ledger that hides nothing does not write the list
		assertFalse(LedgerCodec.toJson(gson, ledger()).contains("hiddenBosses"));
	}

	@Test
	public void rejectsFilesThatAreNotBackups()
	{
		for (String bad : new String[]{"", "not json", "[]", "{}", "{\"app\":\"something-else\"}", "{\"bosses\":{}}"})
		{
			try
			{
				LedgerCodec.fromJson(data, bad);
				fail("accepted: " + bad);
			}
			catch (LedgerCodec.FormatException expected)
			{
				assertFalse(expected.getMessage().isEmpty());
			}
		}
	}

	@Test
	public void replaceWithKeepsListeners()
	{
		Ledger a = ledger();
		Ledger b = ledger();
		b.setKc("zulrah", 9);
		List<String> seen = new ArrayList<>();
		a.addListener(seen::add);
		a.replaceWith(b);
		assertEquals(9, a.kc("zulrah"));
		assertEquals(1, seen.size());
		assertNull(seen.get(0));
		b.setKc("zulrah", 10);
		assertEquals(9, a.kc("zulrah")); // a copy, not shared
	}

	private static final class MemStore implements LedgerStore
	{
		String text;
		int writes;
		boolean quarantined;
		boolean failWrites;
		final CompletableFuture<Void> firstWrite = new CompletableFuture<>();

		@Override
		public String read()
		{
			return text;
		}

		@Override
		public void write(String json) throws IOException
		{
			if (failWrites)
			{
				throw new IOException("disk full");
			}
			text = json;
			writes++;
			firstWrite.complete(null);
		}

		@Override
		public void quarantine()
		{
			quarantined = true;
			text = null;
		}
	}

	@Test
	public void saverWritesAfterTheLastChange() throws Exception
	{
		Ledger l = ledger();
		ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor();
		try
		{
			LedgerSaver saver = new LedgerSaver(l, gson, ex, 50);
			l.addListener(saver);
			MemStore store = new MemStore();
			saver.setStore(store);
			for (int i = 1; i <= 20; i++)
			{
				l.setKc("zulrah", i);
			}
			assertEquals(0, store.writes);
			store.firstWrite.get(3, TimeUnit.SECONDS);
			assertEquals(1, store.writes); // twenty changes, one write
			assertTrue(store.text.contains("\"kc\":20"));
			// nothing changed: flush does nothing
			saver.flushNow();
			assertEquals(1, store.writes);
			// flush writes straight away
			l.setKc("zulrah", 30);
			saver.flushNow();
			assertEquals(2, store.writes);
			assertTrue(store.text.contains("\"kc\":30"));
			// a failing disk is retried at the next change and does not throw
			store.failWrites = true;
			l.setKc("zulrah", 31);
			saver.flushNow();
			assertEquals(2, store.writes);
			store.failWrites = false;
			l.setKc("zulrah", 32);
			saver.flushNow();
			assertEquals(3, store.writes);
			assertTrue(store.text.contains("\"kc\":32"));
		}
		finally
		{
			ex.shutdownNow();
		}
	}

	@Test
	public void switchingAccountsSavesTheFirstBeforeLoadingTheSecond() throws Exception
	{
		Ledger l = ledger();
		ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor();
		try
		{
			LedgerSaver saver = new LedgerSaver(l, gson, ex, 60_000);
			l.addListener(saver);
			MemStore a = new MemStore();
			MemStore b = new MemStore();
			b.text = LedgerCodec.toJson(gson, otherLedger(77));
			saver.setStore(a);
			l.setKc("zulrah", 11);
			// switch: flush a, detach, load b, attach b
			saver.setStore(null);
			assertTrue(a.text.contains("\"kc\":11"));
			assertTrue(saver.loadInto(b));
			saver.setStore(b);
			assertEquals(77, l.kc("zulrah"));
			assertEquals(0, b.writes); // loading did not rewrite the file
			l.setKc("zulrah", 78);
			saver.flushNow();
			assertTrue(b.text.contains("\"kc\":78"));
			assertTrue(a.text.contains("\"kc\":11"));
		}
		finally
		{
			ex.shutdownNow();
		}
	}

	private Ledger otherLedger(int kc)
	{
		Ledger o = ledger();
		o.setKc("zulrah", kc);
		return o;
	}

	@Test
	public void unreadableFileIsSetAsideNotOverwritten() throws Exception
	{
		Ledger l = ledger();
		ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor();
		try
		{
			LedgerSaver saver = new LedgerSaver(l, gson, ex, 10);
			MemStore s = new MemStore();
			s.text = "{ this is not json";
			assertFalse(saver.loadInto(s));
			assertTrue(s.quarantined);
			assertTrue(l.activeBosses().isEmpty());
		}
		finally
		{
			ex.shutdownNow();
		}
	}
}
