package com.dropluckledger.ledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.dropluckledger.TestData;
import com.dropluckledger.engine.Data;
import com.dropluckledger.engine.Engine;
import com.google.gson.Gson;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;

/**
 * Settings of single kills (what the game reveals about one kill), the setting for the kills before tracking began, and older
 * ledgers being brought up to date.
 */
public class KillSettingsTest
{
	private final Data data = TestData.get();

	private Ledger ledger()
	{
		return new Ledger(data, () -> 1_700_000_000_000L);
	}

	private static Object at(BossState s, int kill, String name)
	{
		return s.paramsAt(kill).get(name);
	}

	@Test
	public void oneKillGetsItsOwnValueAndTheOthersKeepTheirs()
	{
		BossState s = new BossState();
		s.kc = 10;
		Map<String, Object> d = Engine.defaultParams(data.boss("general-graardor"));
		assertTrue(Ledger.setKillParamsLocked(s, 4, Collections.singletonMap("looted", 0), d));
		assertEquals(0, at(s, 4, "looted"));
		assertNull(at(s, 3, "looted"));
		assertNull(at(s, 5, "looted"));
		assertEquals(3, s.segs.size());
		// the same value again changes nothing; the neighbour that already says the same is merged
		assertFalse(Ledger.setKillParamsLocked(s, 4, Collections.singletonMap("looted", 0), d));
		assertTrue(Ledger.setKillParamsLocked(s, 5, Collections.singletonMap("looted", 0), d));
		assertEquals(0, at(s, 5, "looted"));
		assertNull(at(s, 6, "looted"));
		assertEquals(3, s.segs.size());
		// and undoing a kill restores it to its neighbours' value
		assertTrue(Ledger.setKillParamsLocked(s, 4, Collections.singletonMap("looted", 100), d));
		assertTrue(Ledger.setKillParamsLocked(s, 5, Collections.singletonMap("looted", 100), d));
		assertEquals(1, s.segs.size());
	}

	@Test
	public void aKillBeforeLaterPeriodsKeepsThem()
	{
		BossState s = new BossState();
		s.kc = 20;
		s.segs.add(new Period(15, Collections.singletonMap("konar", (Object) 1)));
		Map<String, Object> d = Engine.defaultParams(data.boss("general-graardor"));
		Ledger.setKillParamsLocked(s, 7, Collections.singletonMap("looted", 0), d);
		assertEquals(0, at(s, 7, "looted"));
		assertNull(at(s, 8, "looted"));
		assertEquals(1, at(s, 15, "konar"));
		assertNull(at(s, 15, "looted"));
	}

	@Test
	public void theOlderKillsSettingLeavesTheKillsThePluginSawAlone()
	{
		Ledger l = ledger();
		l.setKc("general-graardor", 200);
		l.update("general-graardor", s ->
		{
			s.liveFrom = 151;
			s.seen = new java.util.LinkedHashMap<>(Collections.singletonMap("looted", 151));
			Ledger.setKillParamsLocked(s, 160, Collections.singletonMap("looted", 0), Engine.defaultParams(data.boss("general-graardor")));
			return null;
		});
		l.setParamBefore("general-graardor", "looted", 40);
		BossState s = l.copyOf("general-graardor");
		assertEquals(40, at(s, 1, "looted"));
		assertEquals(40, at(s, 150, "looted"));
		assertNull(at(s, 151, "looted"));
		assertEquals(0, at(s, 160, "looted"));
		assertNull(at(s, 161, "looted"));
		// with no kill seen yet it is every kill
		l.setKc("corporeal-beast", 50);
		l.setParamBefore("corporeal-beast", "looted", 25);
		assertEquals(25, at(l.copyOf("corporeal-beast"), 50, "looted"));
	}

	@Test
	public void aLedgerFromBeforeTheReadKillsWereRememberedReadsFromItsFirstCountedKill() throws Exception
	{
		String json = "{\"app\":\"drop-luck-ledger\",\"v\":1,\"bosses\":{"
			+ "\"tempoross\":{\"kc\":120,\"segs\":[{\"from\":1,\"params\":{}},{\"from\":101,\"params\":{\"fishing\":80}},"
			+ "{\"from\":110,\"params\":{\"fishing\":85}}],\"drops\":[],\"track\":[],\"hide\":[],\"liveFrom\":101},"
			+ "\"general-graardor\":{\"kc\":60,\"segs\":[{\"from\":1,\"params\":{\"looted\":50}},{\"from\":41,\"params\":{}}],"
			+ "\"drops\":[],\"track\":[],\"hide\":[],\"liveFrom\":41}}}";
		Ledger l = LedgerCodec.fromJson(data, json);
		BossState t = l.copyOf("tempoross");
		assertEquals(Integer.valueOf(101), t.seenFrom("fishing"));
		// the games before the first one read take its Fishing level, as they would in a new ledger
		assertEquals(80, at(t, 100, "fishing"));
		assertEquals(85, at(t, 115, "fishing"));
		// a value the player had set is kept
		BossState g = l.copyOf("general-graardor");
		assertEquals(Integer.valueOf(41), g.seenFrom("looted"));
		assertEquals(50, at(g, 40, "looted"));
		// a ledger of this version that read nothing yet is not taken for an older one when read back
		l.update("barrows", st ->
		{
			st.kc = 10;
			st.liveFrom = 5;
			return null;
		});
		BossState back = LedgerCodec.fromJson(data, LedgerCodec.toJson(new Gson(), l)).copyOf("barrows");
		assertNull(back.seenFrom("brothers"));
		// and it is written back
		String again = LedgerCodec.toJson(new Gson(), l);
		assertTrue(again.contains("\"seen\":{\"fishing\":101"));
		assertEquals(Integer.valueOf(101), LedgerCodec.fromJson(data, again).copyOf("tempoross").seenFrom("fishing"));
	}

	@Test
	public void bossesThePluginLeavesOutAreKeptAsTheyWere() throws Exception
	{
		// the Royal Titans are not in the plugin: their entries, old two-titan ones too, stay as they were for the web app
		String json = "{\"app\":\"drop-luck-ledger\",\"v\":1,\"bosses\":{"
			+ "\"branda\":{\"kc\":30,\"segs\":[{\"from\":1,\"params\":{\"share\":50}}],"
			+ "\"drops\":[{\"id\":\"a\",\"item\":\"Giantsoul amulet\",\"kc\":12,\"qty\":1}],\"track\":[],\"hide\":[]},"
			+ "\"royal-titans\":{\"kc\":5,\"segs\":[],\"drops\":[],\"track\":[],\"hide\":[]}}}";
		Ledger l = LedgerCodec.fromJson(data, json);
		assertTrue(l.activeBosses().isEmpty());
		String again = LedgerCodec.toJson(new Gson(), l);
		assertTrue(again.contains("\"branda\":{"));
		assertTrue(again.contains("\"royal-titans\":{"));
		assertTrue(again.contains("\"Giantsoul amulet\""));
	}
}
