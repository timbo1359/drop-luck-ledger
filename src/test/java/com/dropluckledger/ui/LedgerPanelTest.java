package com.dropluckledger.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.dropluckledger.history.HistoryInput;
import com.dropluckledger.history.HistoryPlanner;
import com.dropluckledger.ledger.BossState;
import com.dropluckledger.ledger.Drop;
import com.dropluckledger.ledger.Ledger;
import com.dropluckledger.plugin.TrackingState;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class LedgerPanelTest
{
	private PanelFixture fx;

	@Before
	public void setUp()
	{
		fx = new PanelFixture();
	}

	@After
	public void tearDown()
	{
		fx.close();
	}

	private static void setState(PanelFixture fx, TrackingState s)
	{
		fx.host.state = s;
		fx.panel.stateChanged();
		fx.settle();
	}

	// ------------------------------------------------------------------ what it says when it is not recording

	@Test
	public void itExplainsWhyNothingIsShownWhenNotRecording()
	{
		assertEquals("overview", fx.view());
		for (TrackingState s : new TrackingState[] {TrackingState.STARTING, TrackingState.LOGGED_OUT, TrackingState.PAUSED, TrackingState.LOADING})
		{
			setState(fx, s);
			assertEquals(s.name(), "none", fx.view());
			assertEquals(s.name(), s.message(), fx.panel.stateText());
			assertEquals(s.name(), 1, fx.panel.noteTexts().size());
			assertFalse(s.name(), fx.panel.settingsLink().isVisible());
			assertFalse(s.name(), fx.panel.backButton().isVisible());
			assertEquals(s.name(), "", fx.pageText());
		}
		setState(fx, TrackingState.TRACKING);
		assertEquals("overview", fx.view());
		assertEquals("", fx.panel.stateText());
		assertTrue(fx.panel.noteTexts().isEmpty());
		assertTrue(fx.panel.settingsLink().isVisible());
	}

	@Test
	public void beforeTheLedgerIsReadItSaysSo()
	{
		PanelFixture bare = new PanelFixture(false);
		try
		{
			bare.panel.stateChanged();
			bare.settle();
			assertEquals("none", bare.view());
			assertEquals(TrackingState.LOADING.message(), bare.panel.stateText());
			bare.attach();
			assertEquals("overview", bare.view());
			assertTrue(bare.panel.noteTexts().isEmpty());
		}
		finally
		{
			bare.close();
		}
	}

	@Test
	public void logOutAndInAgainShowsTheNewAccountsFigures()
	{
		fx.ledger.setKc("zulrah", 300);
		fx.settle();
		assertTrue(fx.pageText(), fx.pageText().contains("1 boss tracked"));
		setState(fx, TrackingState.LOGGED_OUT);
		fx.ledger.replaceWith(new Ledger(fx.data, () -> 5L));
		fx.settle();
		assertEquals("none", fx.view());
		Ledger other = new Ledger(fx.data, () -> 5L);
		other.setKc("vorkath", 77);
		other.setKc("nex", 12);
		fx.host.state = TrackingState.LOADING;
		fx.panel.stateChanged();
		fx.ledger.replaceWith(other);
		fx.host.state = TrackingState.TRACKING;
		fx.panel.stateChanged();
		fx.settle();
		assertEquals("overview", fx.view());
		String t = fx.pageText();
		assertTrue(t, t.contains("2 bosses tracked"));
		assertTrue(t, t.contains("Vorkath"));
		assertFalse(t, t.contains("1 boss tracked"));
	}

	// ------------------------------------------------------------------ overview

	@Test
	public void anEmptyLedgerInvitesTheHistoryImport()
	{
		String t = fx.pageText();
		assertTrue(t, t.contains("Nothing tracked yet"));
		JButton b = Swings.button(fx.panel.bodyColumn(), "Add my game history");
		assertNotNull(b);
		Swings.edt(b::doClick);
		assertEquals(1, fx.host.planRequests);
		fx.settle();
		// the fake plugin found nothing: the player is told how to give the plugin something to read
		assertEquals(List.of("note:Game history"), fx.dialogs.asked);
		assertTrue(fx.dialogs.last(), fx.dialogs.last().contains("Boss Kill Log"));
	}

	@Test
	public void theOverviewListsTheBossesTheCharacterHasKilled()
	{
		fx.ledger.setKc("zulrah", 2000);
		fx.ledger.setKc("vorkath", 100);
		fx.ledger.logDrop("vorkath", "Dragonbone necklace", 40, 1, null, Drop.SRC_MANUAL);
		fx.settle();
		OverviewPage o = fx.panel.overviewPage();
		assertEquals(2, o.lastView().tracked);
		String t = fx.pageText();
		assertTrue(t, t.contains("2 bosses tracked"));
		assertTrue(t, t.contains("64 available"));
		assertFalse(t, t.contains("Longest dry streaks"));
		assertFalse(t, t.contains("Driest so far"));
		assertFalse(t, t.contains("Running hot"));
		assertTrue(t, t.contains("Biggest Spoons"));
		assertTrue(t, t.contains("Dragonbone necklace"));
		assertTrue(t, t.contains("My bosses"));
		assertTrue(t, t.contains("2,000"));
		assertTrue(t, t.contains("Zulrah"));
		assertTrue(t, t.contains("Vorkath"));
		assertTrue(t, t.contains("All bosses"));
		assertFalse(t, t.contains("Nothing tracked yet"));
	}

	@Test
	public void theOverviewListsOnlyTheTickedBosses()
	{
		fx.ledger.setKc("zulrah", 300);
		fx.ledger.setKc("duke-sucellus", 40);
		fx.ledger.setShown(java.util.Collections.singletonList("zulrah"), false);
		fx.settle();
		String t = fx.pageText();
		assertTrue(t, t.contains("1 boss tracked \u00b7 63 of 64 shown"));
		assertTrue(t, t.contains("Duke Sucellus"));
		assertFalse(t, t.contains("Zulrah"));
		// the search says when a boss left out matches
		Swings.edt(() -> fx.panel.overviewPage().searchField().setText("zulr"));
		fx.settle();
		t = fx.pageText();
		assertTrue(t, t.contains("No boss or item matches \"zulr\" among the bosses you show."));
		assertTrue(t, t.contains("1 boss you have unticked under Settings, Bosses to show, also matches."));
		Swings.edt(() -> fx.panel.overviewPage().searchField().setText("sucel"));
		fx.settle();
		t = fx.pageText();
		assertTrue(t, t.contains("Duke Sucellus"));
		assertFalse(t, t.contains("unticked"));
		// none of the bosses with kills shown
		Swings.edt(() -> fx.panel.overviewPage().searchField().setText(""));
		fx.ledger.setShown(java.util.Collections.singletonList("duke-sucellus"), false);
		fx.settle();
		t = fx.pageText();
		assertTrue(t, t.contains("None of the bosses you have kills at are ticked"));
		assertFalse(t, t.contains("Nothing tracked yet"));
		assertFalse(t, t.contains("My bosses"));
		assertFalse(t, t.contains("No spoons yet"));
		Swings.edt(Swings.button(fx.panel.bodyColumn(), "Choose bosses")::doClick);
		fx.settle();
		assertEquals("settings", fx.view());
	}

	@Test
	public void theBiggestSpoonsShowTheFirstTenAndTheRestOnRequest()
	{
		// a pet after a few kills at a dozen bosses: a dozen spoons
		int bosses = 0;
		java.util.Map<String, List<String>> logIndex = com.dropluckledger.engine.Catalog.logIndex(fx.data);
		for (com.dropluckledger.engine.Data.Boss b : com.dropluckledger.engine.Catalog.ordered(fx.data))
		{
			for (String n : com.dropluckledger.engine.Catalog.defaultTracked(b))
			{
				List<String> where = logIndex.get(n);
				if (b.meta().get(n).isPet() && where != null && where.size() == 1 && bosses < 14)
				{
					fx.ledger.setKc(b.id, 10);
					fx.ledger.logDrop(b.id, n, 5, 1, null, Drop.SRC_MANUAL);
					bosses++;
					break;
				}
			}
		}
		fx.settle();
		List<Views.Rank> spoons = fx.panel.overviewPage().lastView().spoons;
		assertTrue(spoons.size() + " spoons", spoons.size() > OverviewPage.SPOONS_SHOWN);
		String t = fx.pageText();
		for (int i = 0; i < spoons.size(); i++)
		{
			assertEquals(spoons.get(i).item, i < OverviewPage.SPOONS_SHOWN, t.contains(spoons.get(i).item));
		}
		Swings.edt(Swings.button(fx.panel.bodyColumn(), "Show all " + spoons.size())::doClick);
		fx.settle();
		t = fx.pageText();
		for (Views.Rank r : spoons)
		{
			assertTrue(r.item, t.contains(r.item));
		}
		// it stays open while the figures change, and closes again
		fx.ledger.addKc(spoons.get(0).bossId, 1);
		fx.settle();
		assertTrue(fx.pageText().contains(spoons.get(spoons.size() - 1).item));
		Swings.edt(Swings.button(fx.panel.bodyColumn(), "Show fewer")::doClick);
		fx.settle();
		assertFalse(fx.pageText().contains(spoons.get(spoons.size() - 1).item));
	}

	@Test
	public void theOverviewFollowsTheLedgerWhileItIsOpen()
	{
		fx.ledger.setKc("zulrah", 10);
		fx.settle();
		assertTrue(fx.pageText().contains("1 boss tracked"));
		// kills seen in game arrive from the client thread
		Thread game = new Thread(() ->
		{
			fx.ledger.addKc("vorkath", 1);
			fx.ledger.addKc("nex", 1);
		});
		game.start();
		try
		{
			game.join();
		}
		catch (InterruptedException ex)
		{
			throw new AssertionError(ex);
		}
		fx.settle();
		assertTrue(fx.pageText(), fx.pageText().contains("3 bosses tracked"));
	}

	@Test
	public void searchFindsABossOrTheBossesThatDropAnItem()
	{
		OverviewPage o = fx.panel.overviewPage();
		Swings.edt(() -> o.searchField().setText("zulr"));
		String t = fx.pageText();
		assertTrue(t, t.contains("Zulrah"));
		assertFalse(t, t.contains("Vorkath"));
		Swings.edt(() -> o.searchField().setText("magic fang"));
		t = fx.pageText();
		assertTrue(t, t.contains("Zulrah"));
		assertTrue(t, t.contains("drops Magic fang"));
		Swings.edt(() -> o.searchField().setText("qqqqq"));
		assertTrue(fx.pageText(), fx.pageText().contains("No boss or item matches \"qqqqq\""));
		Swings.edt(() -> o.searchField().setText(""));
		assertTrue(fx.pageText().contains("All bosses"));
	}

	@Test
	public void aBossRowOpensTheBossAndBackComesBack()
	{
		fx.ledger.setKc("zulrah", 250);
		fx.settle();
		JLabel row = Swings.get(() -> Swings.label(fx.panel.overviewPage(), "Zulrah"));
		assertNotNull(row);
		Swings.edt(() -> Swings.click(row));
		fx.settle();
		assertEquals("boss", fx.view());
		assertEquals("Drop Luck Ledger", fx.panel.titleLabel().getText());
		assertTrue("the page names the boss", fx.pageText().startsWith("Zulrah |"));
		assertTrue(fx.panel.backButton().isVisible());
		assertEquals("Overview", fx.panel.backButton().getText());
		assertEquals("zulrah", fx.ledger.prefs().sel);
		Swings.edt(fx.panel.backButton()::doClick);
		fx.settle();
		assertEquals("overview", fx.view());
		assertEquals("Drop Luck Ledger", fx.panel.titleLabel().getText());
		assertFalse(fx.panel.backButton().isVisible());
	}

	@Test
	public void settingsOpensFromAnyPageAndGoesBackToIt()
	{
		fx.ledger.setKc("zulrah", 250);
		fx.openBoss("zulrah");
		Swings.edt(fx.panel.settingsLink()::doClick);
		fx.settle();
		assertEquals("settings", fx.view());
		assertEquals("Settings", fx.panel.titleLabel().getText());
		assertEquals("Back", fx.panel.backButton().getText());
		assertFalse(fx.panel.settingsLink().isVisible());
		Swings.edt(fx.panel.backButton()::doClick);
		fx.settle();
		assertEquals("boss", fx.view());
		assertEquals("zulrah", fx.panel.bossPage().bossId());
		fx.openOverview();
		Swings.edt(fx.panel.settingsLink()::doClick);
		fx.settle();
		Swings.edt(fx.panel.backButton()::doClick);
		fx.settle();
		assertEquals("overview", fx.view());
	}

	// ------------------------------------------------------------------ a boss page

	@Test
	public void theKillCountSpinnerAndTheButtonsEditTheLedger()
	{
		fx.ledger.setKc("zulrah", 500);
		fx.openBoss("zulrah");
		BossPage bp = fx.panel.bossPage();
		assertEquals(500, bp.killSpinner().getValue());
		Swings.edt(() -> bp.killSpinner().setValue(750));
		assertEquals(750, fx.ledger.kc("zulrah"));
		Swings.edt(() -> Swings.button(bp, "+10").doClick());
		assertEquals(760, fx.ledger.kc("zulrah"));
		Swings.edt(() -> Swings.button(bp, "+100").doClick());
		Swings.edt(() -> Swings.button(bp, "+1000").doClick());
		assertEquals(1860, fx.ledger.kc("zulrah"));
		fx.settle();
		assertEquals(1860, bp.killSpinner().getValue());
	}

	@Test
	public void killsSeenInGameShowInTheOpenPage()
	{
		fx.ledger.setKc("zulrah", 500);
		fx.openBoss("zulrah");
		BossPage bp = fx.panel.bossPage();
		fx.ledger.addKc("zulrah", 3);
		fx.settle();
		assertEquals(503, bp.killSpinner().getValue());
		assertEquals(503, fx.ledger.kc("zulrah"));
		// and the figures were worked out again
		assertEquals(503, bp.lastView().kc);
	}

	@Test
	public void theTrackerShowsACardForEachTrackedItem()
	{
		fx.ledger.setKc("zulrah", 500);
		fx.ledger.logDrop("zulrah", "Magic fang", 120, 1, null, Drop.SRC_MANUAL);
		fx.openBoss("zulrah");
		BossPage bp = fx.panel.bossPage();
		List<ItemCard> cards = Swings.get(() -> Swings.all(bp.contentColumn(), ItemCard.class));
		assertEquals(bp.lastView().items.size(), cards.size());
		assertFalse(cards.isEmpty());
		ItemCard fang = null;
		for (ItemCard c : cards)
		{
			if (c.view().name.equals("Magic fang"))
			{
				fang = c;
			}
		}
		assertNotNull(fang);
		assertEquals(1, fang.view().a.O);
		assertEquals(380, fang.view().a.streak);
		String t = fx.pageText();
		assertTrue(t, t.contains("Drop history"));
		assertTrue(t, t.contains("1 logged"));
		assertTrue(t, t.contains("kill 120"));
		assertTrue(t, t.contains("Items I already own"));
		assertFalse(t, t.contains("No kills yet"));
	}

	@Test
	public void aBossWithNoKillsSaysSo()
	{
		fx.openBoss("vorkath");
		String t = fx.pageText();
		assertTrue(t, t.contains("No kills yet"));
		assertTrue(t, t.contains("Nothing logged yet"));
	}

	@Test
	public void cardDetailsOpenAndClose()
	{
		fx.ledger.setKc("zulrah", 500);
		fx.openBoss("zulrah");
		BossPage bp = fx.panel.bossPage();
		assertFalse(bp.isOpen("Magic fang"));
		ItemCard before = cardFor(bp, "Magic fang");
		Swings.edt(() -> before.toggle());
		assertTrue(bp.isOpen("Magic fang"));
		ItemCard after = cardFor(bp, "Magic fang");
		assertNotNull(after.chart());
		fx.settle();
		Swings.edt(() -> cardFor(bp, "Magic fang").toggle());
		assertFalse(bp.isOpen("Magic fang"));
	}

	private static ItemCard cardFor(BossPage bp, String name)
	{
		return Swings.get(() ->
		{
			for (ItemCard c : Swings.all(bp.contentColumn(), ItemCard.class))
			{
				if (c.view().name.equals(name))
				{
					return c;
				}
			}
			return null;
		});
	}

	@Test
	public void theTabsSwitchAndAreRemembered()
	{
		fx.ledger.setKc("zulrah", 500);
		fx.openBoss("zulrah");
		BossPage bp = fx.panel.bossPage();
		assertEquals(BossPage.TRACKER, bp.tab());
		Swings.edt(() -> bp.selectTab(BossPage.TABLE));
		fx.settle();
		String t = fx.pageText();
		assertTrue(t, t.contains("Per-kill chance with your current settings"));
		assertTrue(t, t.contains("Magic fang"));
		assertEquals(BossPage.TABLE, fx.ledger.prefs().tab);
		Swings.edt(() -> bp.selectTab(BossPage.ABOUT));
		t = fx.pageText();
		assertTrue(t, t.contains("How Zulrah works"));
		assertTrue(t, t.contains("Data confidence"));
		assertEquals(BossPage.ABOUT, fx.ledger.prefs().tab);
		// opening another boss starts on the tab used last
		fx.openBoss("vorkath");
		assertEquals(BossPage.ABOUT, fx.panel.bossPage().tab());
	}

	@Test
	public void theDropTableFilters()
	{
		fx.ledger.setKc("zulrah", 10);
		fx.openBoss("zulrah");
		BossPage bp = fx.panel.bossPage();
		Swings.edt(() -> bp.selectTab(BossPage.TABLE));
		fx.settle();
		String all = fx.pageText();
		assertTrue(all, all.contains("Magic fang"));
		assertTrue(all, all.contains("Tanzanite fang"));
		Swings.edt(() -> bp.filterField().setText("magic f"));
		String some = fx.pageText();
		assertTrue(some, some.contains("Magic fang"));
		assertFalse(some, some.contains("Tanzanite fang"));
		assertFalse(some, some.contains("Magic seed"));
		assertTrue(some, some.contains("1 item"));
	}

	// ------------------------------------------------------------------ settings of a boss

	@Test
	public void aSettingChangeIsWrittenToTheLedger()
	{
		fx.ledger.setKc("zulrah", 500);
		fx.openBoss("zulrah");
		BossPage bp = fx.panel.bossPage();
		JCheckBox ca = (JCheckBox) bp.paramControls().get("ca");
		assertFalse(ca.isSelected());
		Swings.edt(ca::doClick);
		assertEquals(1, ((Number) fx.ledger.currentParams("zulrah").get("ca")).intValue());
		assertEquals(1, fx.ledger.copyOf("zulrah").segs.size());
		fx.settle();
		assertTrue(((JCheckBox) bp.paramControls().get("ca")).isSelected());
		Swings.edt(ca::doClick);
		assertEquals(0, ((Number) fx.ledger.currentParams("zulrah").get("ca")).intValue());
	}

	@Test
	public void anIntegerAndAChoiceSettingAreWrittenToo()
	{
		fx.ledger.setKc("barrows", 100);
		fx.openBoss("barrows");
		BossPage bp = fx.panel.bossPage();
		javax.swing.JSpinner brothers = (javax.swing.JSpinner) bp.paramControls().get("brothers");
		Swings.edt(() -> brothers.setValue(4));
		assertEquals(4, ((Number) fx.ledger.currentParams("barrows").get("brothers")).intValue());
		fx.ledger.setKc("the-mimic", 20);
		fx.openBoss("the-mimic");
		@SuppressWarnings("unchecked")
		JComboBox<String> tier = (JComboBox<String>) fx.panel.bossPage().paramControls().get("tier");
		Swings.edt(() -> tier.setSelectedItem("master"));
		assertEquals("master", fx.ledger.currentParams("the-mimic").get("tier"));
	}

	@Test
	public void teamPlayIsExplainedOnTheBossPage()
	{
		fx.ledger.setKc("callisto", 50);
		fx.openBoss("callisto");
		assertTrue(Swings.text(fx.panel.bossPage()), Swings.text(fx.panel.bossPage()).contains("Solo rates are assumed"));
		javax.swing.JSpinner share = (javax.swing.JSpinner) fx.panel.bossPage().paramControls().get("share");
		Swings.edt(() -> share.setValue(50));
		fx.settle();
		assertFalse(Swings.text(fx.panel.bossPage()).contains("Solo rates are assumed"));

		fx.ledger.setKc("general-graardor", 300);
		fx.ledger.update("general-graardor", st ->
		{
			st.liveFrom = 201;
			st.seen = new java.util.LinkedHashMap<>(java.util.Collections.singletonMap("looted", 201));
			java.util.Map<String, Object> d = com.dropluckledger.engine.Engine.defaultParams(fx.data.boss("general-graardor"));
			Ledger.setKillParamsLocked(st, 250, java.util.Collections.singletonMap("looted", (Object) 0), d);
			Ledger.setKillParamsLocked(st, 251, java.util.Collections.singletonMap("looted", (Object) 0), d);
			return null;
		});
		fx.openBoss("general-graardor");
		assertTrue(Swings.text(fx.panel.bossPage()), Swings.text(fx.panel.bossPage()).contains("2 kills where another player got the loot"));
		assertTrue(Swings.text(fx.panel.bossPage()), Swings.text(fx.panel.bossPage()).contains("your values are for kills 1\u2013200, the ones it did not see"));
		assertTrue(Swings.text(fx.panel.bossPage()), Swings.text(fx.panel.bossPage()).contains("Read from the game for kills 201\u2013300: the loot was yours for 98 of 100."));
		// the setting is for the kills before the plugin saw any; the kills it saw keep what the game said
		javax.swing.JSpinner looted = (javax.swing.JSpinner) fx.panel.bossPage().paramControls().get("looted");
		Swings.edt(() -> looted.setValue(30));
		fx.settle();
		BossState g = fx.ledger.copyOf("general-graardor");
		assertEquals(30, g.paramsAt(200).get("looted"));
		assertEquals(0, g.paramsAt(250).get("looted"));
		assertNull(g.paramsAt(260).get("looted"));
		assertEquals(30, looted.getValue());
	}

	@Test
	public void aSettingTheGameRevealsIsSetForTheOtherKills()
	{
		fx.ledger.setKc("hespori", 40);
		com.dropluckledger.capture.Capture cap = new com.dropluckledger.capture.Capture(fx.ledger, new com.dropluckledger.capture.BossIndex(fx.data));
		cap.onKillCount("Hespori", 41, 100);
		cap.onLoot(new com.dropluckledger.capture.Capture.Loot("Hespori", 1, java.util.Collections.emptyList(), java.util.Collections.singletonMap("farming", 70), 102));
		cap.onKillCount("Hespori", 42, 200);
		cap.onLoot(new com.dropluckledger.capture.Capture.Loot("Hespori", 1, java.util.Collections.emptyList(), java.util.Collections.singletonMap("farming", 72), 202));
		fx.openBoss("hespori");
		String text = Swings.text(fx.panel.bossPage());
		assertTrue(text, text.contains("your values are for kills 1\u201340, the ones it did not see"));
		assertTrue(text, text.contains("Read from the game for kills 41\u201342: Farming level: 70 \u00d71, 72 \u00d71."));
		javax.swing.JSpinner farming = (javax.swing.JSpinner) fx.panel.bossPage().paramControls().get("farming");
		// the kills before took the first level the plugin read
		assertEquals(70, farming.getValue());
		Swings.edt(() -> farming.setValue(60));
		fx.settle();
		BossState t = fx.ledger.copyOf("hespori");
		assertEquals(60, t.paramsAt(40).get("farming"));
		assertEquals(70, t.paramsAt(41).get("farming"));
		assertEquals(72, t.paramsAt(42).get("farming"));
		assertEquals(60, farming.getValue());
		// the choice of kills to apply a change to is only for the settings the player sets
		assertFalse(fx.panel.bossPage().scopeShown());
		fx.ledger.setKc("duke-sucellus", 3);
		fx.openBoss("duke-sucellus");
		assertTrue(fx.panel.bossPage().scopeShown());
	}

	@Test
	public void theNoteSaysWhichKillsThePlayersValuesAreFor()
	{
		java.util.Map<String, Integer> seen = new java.util.LinkedHashMap<>();
		seen.put("Blue Moon defeated this run", null);
		assertEquals("For the settings the plugin reads from the game, your values are for the kills it has not seen.", BossPage.readNote(seen));
		seen.put("Blue Moon defeated this run", 2);
		assertEquals("For the settings the plugin reads from the game, your values are for kill 1, the ones it did not see.", BossPage.readNote(seen));
		seen.put("Blood Moon defeated this run", 1);
		assertEquals("For the settings the plugin reads from the game, your values are for the kills it did not see "
			+ "(Blue Moon defeated this run: kill 1; Blood Moon defeated this run: none).", BossPage.readNote(seen));
		seen.put("Blue Moon defeated this run", 1);
		assertEquals("The plugin read the settings it can from the game for every kill.", BossPage.readNote(seen));
	}

	@Test
	public void itemsThePluginHidesAreNotOnTheBossPage()
	{
		fx.ledger.setKc("barrows", 50);
		fx.ledger.logDrop("barrows", "Ahrim's hood", 10, 1, null, Drop.SRC_MANUAL);
		// a backup from the web page can still have them
		fx.ledger.logDrop("barrows", "Bolt rack", 20, 35, null, Drop.SRC_MANUAL);
		fx.ledger.setTracked("barrows", "Bolt rack", true);
		fx.openBoss("barrows");
		BossPage bp = fx.panel.bossPage();
		String t = Swings.text(bp);
		assertTrue(t, t.contains("Ahrim's hood"));
		assertTrue(t, t.contains("1 logged"));
		assertFalse(t, t.contains("Bolt rack"));
		assertFalse(bp.lastView().addable.contains("Bolt rack"));
		assertFalse(bp.lastView().addable.contains("Loop half of key"));
		// nor on the drop table
		Swings.edt(() -> bp.selectTab(BossPage.TABLE));
		fx.settle();
		t = Swings.text(fx.panel.bossPage());
		assertTrue(t, t.contains("Ahrim's hood"));
		assertFalse(t, t.contains("Bolt rack"));
		assertFalse(t, t.contains("half of key"));
	}

	@Test
	public void selectSettingsShowTheirOptionsCapitalised()
	{
		fx.ledger.setKc("maggot-king", 5);
		fx.openBoss("maggot-king");
		assertTrue(Swings.text(fx.panel.bossPage()), Swings.text(fx.panel.bossPage()).contains("Reward option: Stomach"));
	}

	@Test
	public void newKillsOnlyStartsASettingPeriodAndTheyCanBeMerged()
	{
		fx.ledger.setKc("zulrah", 500);
		fx.openBoss("zulrah");
		BossPage bp = fx.panel.bossPage();
		Swings.edt(() -> bp.scopeBox().setSelectedIndex(1));
		assertEquals("new", fx.ledger.prefs().scope);
		JCheckBox ca = (JCheckBox) bp.paramControls().get("ca");
		Swings.edt(ca::doClick);
		fx.settle();
		BossState st = fx.ledger.copyOf("zulrah");
		assertEquals(2, st.segs.size());
		assertEquals(501, st.segs.get(1).from);
		assertTrue(Swings.text(bp), Swings.text(bp).contains("2 setting periods"));
		JButton merge = Swings.button(bp, "Merge into one");
		assertTrue(merge.isVisible());
		// merging loses what the game said about single kills, so it asks first
		fx.dialogs.confirmAnswer = false;
		Swings.edt(merge::doClick);
		fx.settle();
		assertEquals(2, fx.ledger.copyOf("zulrah").segs.size());
		assertTrue(fx.dialogs.asked.contains("confirm:Merge settings"));
		fx.dialogs.confirmAnswer = true;
		Swings.edt(merge::doClick);
		fx.settle();
		assertEquals(1, fx.ledger.copyOf("zulrah").segs.size());
		assertFalse(merge.isVisible());
	}

	@Test
	public void theBossSettingsFoldRemembersItsState()
	{
		fx.openBoss("zulrah");
		BossPage bp = fx.panel.bossPage();
		Fold settings = Swings.first(bp, Fold.class, f -> true);
		assertNotNull(settings);
		assertFalse(Swings.get(settings::isOpen));
		Swings.edt(((JButton) settings.header())::doClick);
		assertTrue(Swings.get(settings::isOpen));
		fx.openBoss("vorkath");
		Fold again = Swings.first(fx.panel.bossPage(), Fold.class, f -> true);
		assertTrue("the fold stays open from boss to boss", Swings.get(again::isOpen));
	}

	// ------------------------------------------------------------------ logging and editing drops

	@Test
	public void aDropLoggedThroughTheFormIsInTheLedger()
	{
		fx.ledger.setKc("zulrah", 300);
		fx.openBoss("zulrah");
		fx.dialogs.fill = f ->
		{
			LogForm lf = (LogForm) f;
			lf.itemBox().setSelectedItem("Magic fang");
			lf.killSpinner().setValue(120);
		};
		BossPage bp = fx.panel.bossPage();
		Swings.edt(() -> bp.logForm(null, null));
		assertEquals("Log a drop · Zulrah", fx.dialogs.lastTitle);
		List<Drop> drops = fx.ledger.copyOf("zulrah").drops;
		assertEquals(1, drops.size());
		assertEquals("Magic fang", drops.get(0).item);
		assertEquals(Integer.valueOf(120), drops.get(0).kc);
		assertEquals(1, drops.get(0).qty);
		assertEquals(Drop.SRC_MANUAL, drops.get(0).src);
		fx.settle();
		assertTrue(fx.panel.noteTexts().toString(), fx.panel.noteTexts().get(0).equals("Logged Magic fang at kill 120"));
		assertEquals(1, cardFor(bp, "Magic fang").view().a.O);
	}

	@Test
	public void theCardsLogButtonStartsWithItsItem()
	{
		fx.ledger.setKc("zulrah", 300);
		fx.openBoss("zulrah");
		fx.dialogs.fill = f -> assertEquals("Tanzanite fang", ((LogForm) f).itemBox().getSelectedItem());
		BossPage bp = fx.panel.bossPage();
		Swings.edt(() -> bp.log("Tanzanite fang"));
		// the form was cancelled by default fill()? No: it was accepted, so a drop exists
		assertEquals(1, fx.ledger.copyOf("zulrah").drops.size());
		assertEquals("Tanzanite fang", fx.ledger.copyOf("zulrah").drops.get(0).item);
	}

	@Test
	public void cancellingTheFormLogsNothing()
	{
		fx.ledger.setKc("zulrah", 300);
		fx.openBoss("zulrah");
		fx.dialogs.formAnswer = false;
		fx.dialogs.fill = f -> ((LogForm) f).itemBox().setSelectedItem("Magic fang");
		Swings.edt(() -> fx.panel.bossPage().logForm(null, null));
		assertTrue(fx.ledger.copyOf("zulrah").drops.isEmpty());
		assertTrue(fx.panel.noteTexts().isEmpty());
	}

	@Test
	public void aFormWithoutAnItemIsSentBack()
	{
		fx.ledger.setKc("zulrah", 300);
		fx.openBoss("zulrah");
		int[] shown = {0};
		fx.dialogs.fill = f ->
		{
			// the first time nothing is chosen, so the panel complains and shows the form again
			if (shown[0]++ == 1)
			{
				((LogForm) f).itemBox().setSelectedItem("Magic fang");
			}
		};
		Swings.edt(() -> fx.panel.bossPage().logForm(null, null));
		assertEquals(2, shown[0]);
		assertEquals("Choose an item.", fx.dialogs.last());
		assertEquals(1, fx.ledger.copyOf("zulrah").drops.size());
	}

	@Test
	public void aDropWithAnUnknownKillIsKeptWithoutAKillNumber()
	{
		fx.ledger.setKc("zulrah", 300);
		fx.openBoss("zulrah");
		fx.dialogs.fill = f ->
		{
			LogForm lf = (LogForm) f;
			lf.itemBox().setSelectedItem("Magic fang");
			lf.unknownBox().doClick();
			lf.quantitySpinner().setValue(2);
		};
		Swings.edt(() -> fx.panel.bossPage().logForm(null, null));
		Drop d = fx.ledger.copyOf("zulrah").drops.get(0);
		assertNull(d.kc);
		assertEquals(2, d.qty);
		fx.settle();
		assertTrue(fx.pageText(), fx.pageText().contains("kill unknown"));
	}

	@Test
	public void aLoggedDropCanBeEditedAndDeleted()
	{
		fx.ledger.setKc("zulrah", 300);
		Drop d = fx.ledger.logDrop("zulrah", "Magic fang", 100, 1, null, Drop.SRC_MANUAL);
		fx.openBoss("zulrah");
		fx.dialogs.fill = f -> ((LogForm) f).killSpinner().setValue(150);
		Swings.edt(() -> Swings.button(fx.panel.bossPage(), "Edit").doClick());
		assertEquals("Edit drop · Zulrah", fx.dialogs.lastTitle);
		assertEquals(Integer.valueOf(150), fx.ledger.copyOf("zulrah").drops.get(0).kc);
		assertEquals(d.id, fx.ledger.copyOf("zulrah").drops.get(0).id);
		fx.settle();
		// delete asks first
		fx.dialogs.confirmAnswer = false;
		JButton del = Swings.get(() -> Swings.button(fx.panel.bossPage(), "Delete this drop"));
		Swings.edt(del::doClick);
		assertEquals(1, fx.ledger.copyOf("zulrah").drops.size());
		assertEquals("Delete Magic fang at kill 150 from your history?", fx.dialogs.last());
		fx.dialogs.confirmAnswer = true;
		Swings.edt(del::doClick);
		assertTrue(fx.ledger.copyOf("zulrah").drops.isEmpty());
		fx.settle();
		assertTrue(fx.pageText().contains("Nothing logged yet"));
	}

	@Test
	public void itemsAlreadyOwnedAreAddedWithoutAKillNumber()
	{
		fx.ledger.setKc("zulrah", 300);
		fx.openBoss("zulrah");
		fx.dialogs.fill = f ->
		{
			OwnedForm of = (OwnedForm) f;
			assertTrue(of.spinners().containsKey("Tanzanite fang"));
			of.spinners().get("Tanzanite fang").setValue(2);
		};
		Swings.edt(() -> fx.panel.bossPage().ownedForm());
		BossState st = fx.ledger.copyOf("zulrah");
		assertEquals(2, st.copies("Tanzanite fang"));
		for (Drop d : st.drops)
		{
			assertNull(d.kc);
		}
		fx.settle();
		assertEquals("Added 2 owned items", fx.panel.noteTexts().get(0));
		// asking again with nothing changed adds nothing
		fx.dialogs.fill = f ->
		{
		};
		Swings.edt(() -> fx.panel.bossPage().ownedForm());
		assertEquals(2, fx.ledger.copyOf("zulrah").copies("Tanzanite fang"));
		assertEquals("No changes", fx.panel.noteTexts().get(0));
	}

	@Test
	public void anItemFromTheListCanBeTrackedAndHidden()
	{
		fx.ledger.setKc("zulrah", 300);
		fx.openBoss("zulrah");
		BossPage bp = fx.panel.bossPage();
		List<String> hidden = bp.lastView().addable;
		assertFalse(hidden.isEmpty());
		String name = hidden.get(0);
		@SuppressWarnings("unchecked")
		JComboBox<String> box = Swings.get(() -> Swings.first(bp.contentColumn(), JComboBox.class, c -> c.getItemCount() > 1 && "Choose an item…".equals(c.getItemAt(0))));
		assertNotNull(box);
		Swings.edt(() -> box.setSelectedItem(name));
		fx.settle();
		assertTrue(fx.ledger.trackedNames("zulrah").contains(name));
		assertNotNull(cardFor(bp, name));
		Swings.edt(() -> cardFor(bp, name).hideButton().doClick());
		fx.settle();
		assertFalse(fx.ledger.trackedNames("zulrah").contains(name));
	}

	// ------------------------------------------------------------------ the game's history

	private HistoryPlanner.Plan planWithZulrah()
	{
		HistoryInput in = new HistoryInput();
		in.addKc("Zulrah", 1234, "Boss Kill Log");
		in.addKc("Vorkath", 300, "Boss Kill Log");
		in.addOwned(null, "Zulrah", "Tanzanite fang", 2, "Collection log");
		return fx.planner.plan(in);
	}

	@Test
	public void historyIsOfferedAndNothingIsAddedUntilTheReviewIsAccepted()
	{
		HistoryPlanner.Plan plan = planWithZulrah();
		assertEquals(2, plan.kcToApply());
		fx.panel.offerHistory(plan);
		fx.settle();
		assertSame(plan, fx.panel.offeredPlan());
		assertEquals(1, fx.panel.noteTexts().size());
		assertTrue(fx.panel.noteTexts().get(0), fx.panel.noteTexts().get(0).contains("2 kill counts and 2 owned items"));
		assertEquals(0, fx.ledger.kc("zulrah"));
		List<JButton> buttons = Swings.get(() -> fx.panel.noteButtons("offer"));
		assertEquals(2, buttons.size());
		assertEquals("Look at it", buttons.get(0).getText());
		fx.dialogs.fill = f ->
		{
			HistoryForm hf = (HistoryForm) f;
			assertSame(plan, hf.plan());
			hf.tickKc(1, false);
		};
		Swings.edt(buttons.get(0)::doClick);
		fx.settle();
		assertEquals(List.of("form:Add my game history"), fx.dialogs.asked);
		assertEquals(1234, fx.ledger.kc("zulrah"));
		assertEquals(0, fx.ledger.kc("vorkath"));
		assertEquals(2, fx.ledger.copyOf("zulrah").copies("Tanzanite fang"));
		assertNull(fx.panel.offeredPlan());
		assertEquals("Added 1 kill count and 2 owned items", fx.panel.noteTexts().get(0));
	}

	private static void assertSame(Object a, Object b)
	{
		org.junit.Assert.assertSame(a, b);
	}

	@Test
	public void historyCanBeLeftForLater()
	{
		fx.panel.offerHistory(planWithZulrah());
		fx.settle();
		List<JButton> buttons = Swings.get(() -> fx.panel.noteButtons("offer"));
		Swings.edt(buttons.get(1)::doClick);
		fx.settle();
		assertTrue(fx.panel.noteTexts().isEmpty());
		assertNull(fx.panel.offeredPlan());
		assertEquals(0, fx.ledger.kc("zulrah"));
		assertTrue(fx.dialogs.asked.isEmpty());
	}

	@Test
	public void cancellingTheHistoryReviewAddsNothing()
	{
		fx.panel.offerHistory(planWithZulrah());
		fx.settle();
		fx.dialogs.formAnswer = false;
		Swings.edt(Swings.get(() -> fx.panel.noteButtons("offer")).get(0)::doClick);
		fx.settle();
		assertEquals(0, fx.ledger.kc("zulrah"));
		assertTrue(fx.ledger.copyOf("zulrah").drops.isEmpty());
	}

	@Test
	public void aKillLogPageTheGameShowedCanBeAddedFromTheNote()
	{
		fx.panel.historyRead("Boss Kill Log: 2 bosses read");
		fx.settle();
		assertEquals(List.of("Boss Kill Log: 2 bosses read"), fx.panel.noteTexts());
		List<JButton> buttons = Swings.get(() -> fx.panel.noteButtons("read"));
		assertEquals(1, buttons.size());
		assertEquals("Add to my ledger…", buttons.get(0).getText());
		fx.host.plan = planWithZulrah();
		Swings.edt(buttons.get(0)::doClick);
		fx.settle();
		assertEquals(1, fx.host.planRequests);
		assertEquals(1234, fx.ledger.kc("zulrah"));
		assertEquals(300, fx.ledger.kc("vorkath"));
		// the note about the page read is gone once its contents are in
		assertEquals(List.of("Added 2 kill counts and 2 owned items"), fx.panel.noteTexts());
	}

	@Test
	public void aSecondPageReadReplacesTheFirstNote()
	{
		fx.panel.historyRead("Collection Log page read: Zulrah");
		fx.panel.historyRead("Collection Log page read: Vorkath");
		fx.settle();
		assertEquals(List.of("Collection Log page read: Vorkath"), fx.panel.noteTexts());
	}

	@Test
	public void whenTheLedgerAlreadyHasEverythingTheReviewSaysSo()
	{
		fx.planner.apply(planWithZulrah());
		fx.host.plan = planWithZulrah();
		Swings.edt(() -> fx.panel.context().reviewHistory());
		fx.settle();
		assertEquals(List.of("note:Game history"), fx.dialogs.asked);
		assertTrue(fx.dialogs.last(), fx.dialogs.last().contains("already has everything"));
	}

	@Test
	public void reviewingHistoryNeedsALoggedInCharacter()
	{
		setState(fx, TrackingState.LOGGED_OUT);
		Swings.edt(() -> fx.panel.context().reviewHistory());
		assertEquals(0, fx.host.planRequests);
		assertTrue(fx.dialogs.last(), fx.dialogs.last().contains("Log in"));
	}

	// ------------------------------------------------------------------ messages

	@Test
	public void messagesAppearAndCanBeDismissed()
	{
		fx.panel.showMessage("Logged a Tanzanite fang from Zulrah");
		fx.settle();
		assertEquals(List.of("Logged a Tanzanite fang from Zulrah"), fx.panel.noteTexts());
		JButton x = Swings.get(() -> Swings.first(fx.panel, JButton.class, b -> "Dismiss".equals(b.getToolTipText())));
		assertNotNull(x);
		Swings.edt(x::doClick);
		assertTrue(fx.panel.noteTexts().isEmpty());
	}

	@Test
	public void messagesAreKeptToAFew()
	{
		for (int i = 0; i < 9; i++)
		{
			fx.panel.showMessage("note " + i);
		}
		fx.settle();
		List<String> n = fx.panel.noteTexts();
		assertEquals(4, n.size());
		assertEquals("note 8", n.get(0));
		assertEquals("note 5", n.get(3));
	}

	@Test
	public void messagesFromThePluginShowInEveryState()
	{
		setState(fx, TrackingState.LOADING);
		fx.panel.showMessage("Your saved ledger could not be read");
		fx.settle();
		assertEquals(List.of("Your saved ledger could not be read", TrackingState.LOADING.message()), fx.panel.noteTexts());
		// going on to recording takes the state note away but not the message
		setState(fx, TrackingState.TRACKING);
		assertEquals(List.of("Your saved ledger could not be read"), fx.panel.noteTexts());
	}

	// ------------------------------------------------------------------ the end

	@Test
	public void switchingThePluginOffLeavesNothingBehind()
	{
		fx.ledger.setKc("zulrah", 20);
		fx.openBoss("zulrah");
		fx.panel.showMessage("hello");
		fx.settle();
		Swings.edt(fx.panel::detach);
		fx.settle();
		assertEquals("none", fx.view());
		assertTrue(fx.panel.noteTexts().isEmpty());
		assertEquals(0, fx.panel.bodyColumn().getComponentCount());
		// the ledger no longer reaches the panel
		fx.ledger.addKc("zulrah", 1);
		fx.settle();
		assertEquals("none", fx.view());
		assertTrue(fx.panel.idle());
	}
}
