package com.dropluckledger.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.dropluckledger.engine.Catalog;
import com.dropluckledger.history.HistoryInput;
import com.dropluckledger.history.HistoryPlanner;
import com.dropluckledger.ledger.Drop;
import java.util.List;
import java.util.Map;
import javax.swing.JSpinner;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class FormsTest
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

	private String sharedItem()
	{
		for (Map.Entry<String, List<String>> e : Catalog.logIndex(fx.data).entrySet())
		{
			if (e.getValue().size() >= 2)
			{
				return e.getKey();
			}
		}
		throw new AssertionError("no shared item");
	}

	private LogForm logForm(String boss, Drop edit, String item)
	{
		return Swings.get(() -> new LogForm(fx.ledger, new Analyzer(fx.ledger, null), boss, edit, item));
	}

	@Test
	public void theLogFormStartsOnTheCurrentKill()
	{
		fx.ledger.setKc("zulrah", 321);
		LogForm f = logForm("zulrah", null, "Magic fang");
		assertEquals("Magic fang", f.itemBox().getSelectedItem());
		assertEquals(321, f.killSpinner().getValue());
		assertEquals(1, f.quantitySpinner().getValue());
		assertFalse(f.unknownBox().isSelected());
		assertNull(f.problem());
		assertEquals("Magic fang at kill 321", f.save());
		assertEquals(1, fx.ledger.copyOf("zulrah").drops.size());
	}

	@Test
	public void theLogFormOffersEveryItemOnTheTableAfterTheTrackedOnes()
	{
		LogForm f = logForm("zulrah", null, null);
		assertNull(f.itemBox().getSelectedItem());
		assertEquals("Choose an item.", f.problem());
		List<String> tracked = fx.ledger.trackedNames("zulrah");
		for (int i = 0; i < tracked.size(); i++)
		{
			assertEquals(tracked.get(i), f.itemBox().getItemAt(i));
		}
		assertTrue(f.itemBox().getItemCount() >= tracked.size());
	}

	@Test
	public void unknownKillTurnsTheKillNumberOff()
	{
		fx.ledger.setKc("zulrah", 50);
		LogForm f = logForm("zulrah", null, "Magic fang");
		assertTrue(f.killSpinner().isEnabled());
		Swings.edt(f.unknownBox()::doClick);
		assertFalse(f.killSpinner().isEnabled());
		assertEquals("Magic fang", f.save());
		assertNull(fx.ledger.copyOf("zulrah").drops.get(0).kc);
	}

	@Test
	public void editingKeepsTheDropAndItsKillNumber()
	{
		fx.ledger.setKc("zulrah", 400);
		Drop d = fx.ledger.logDrop("zulrah", "Magic fang", 123, 2, null, Drop.SRC_MANUAL);
		LogForm f = logForm("zulrah", fx.ledger.copyOf("zulrah").drops.get(0), null);
		assertEquals("Magic fang", f.itemBox().getSelectedItem());
		assertEquals(123, f.killSpinner().getValue());
		assertEquals(2, f.quantitySpinner().getValue());
		Swings.edt(() -> f.quantitySpinner().setValue(3));
		f.save();
		Drop now = fx.ledger.copyOf("zulrah").drops.get(0);
		assertEquals(d.id, now.id);
		assertEquals(3, now.qty);
		assertEquals(Integer.valueOf(123), now.kc);
	}

	@Test
	public void aSharedItemAsksForTheOtherBossesKillCounts()
	{
		String item = sharedItem();
		List<String> bosses = Catalog.logIndex(fx.data).get(item);
		String a = bosses.get(0);
		String b = bosses.get(1);
		fx.ledger.setKc(a, 400);
		fx.ledger.setKc(b, 250);
		LogForm f = logForm(a, null, item);
		assertEquals(1, f.snapshotSpinners().size());
		JSpinner other = f.snapshotSpinners().get(b);
		assertNotNull(other);
		assertEquals("filled in with the current count", 250, other.getValue());
		Swings.edt(() -> other.setValue(180));
		f.save();
		Drop d = fx.ledger.copyOf(a).drops.get(0);
		assertEquals(Integer.valueOf(180), d.snap.get(b));
	}

	@Test
	public void aSharedItemWithNoOtherKillsAsksForNothing()
	{
		String item = sharedItem();
		List<String> bosses = Catalog.logIndex(fx.data).get(item);
		fx.ledger.setKc(bosses.get(0), 400);
		LogForm f = logForm(bosses.get(0), null, item);
		assertTrue(f.snapshotSpinners().isEmpty());
		f.save();
		assertNull(fx.ledger.copyOf(bosses.get(0)).drops.get(0).snap);
	}

	@Test
	public void theOwnedFormListsTheUniquesAndSaysWhatItAdded()
	{
		fx.ledger.setKc("zulrah", 100);
		fx.ledger.addOwned("zulrah", "Tanzanite fang", 1);
		OwnedForm f = Swings.get(() -> new OwnedForm(fx.panel.context(), "zulrah"));
		JSpinner s = f.spinners().get("Tanzanite fang");
		assertEquals("starts at what is already there", 1, s.getValue());
		assertEquals(0, f.save());
		Swings.edt(() -> s.setValue(4));
		assertEquals(3, f.save());
		assertEquals(4, fx.ledger.copyOf("zulrah").copies("Tanzanite fang"));
		// lowering it does not remove anything
		Swings.edt(() -> s.setValue(0));
		assertEquals(0, f.save());
		assertEquals(4, fx.ledger.copyOf("zulrah").copies("Tanzanite fang"));
	}

	// ------------------------------------------------------------------ the history review

	private HistoryPlanner.Plan plan()
	{
		HistoryInput in = new HistoryInput();
		in.addKc("Zulrah", 900, "Boss Kill Log");
		in.addKc("Vorkath", 150, "Boss Kill Log");
		in.addKc("Goblin", 3, "Boss Kill Log");
		in.addOwned(null, "Zulrah", "Tanzanite fang", 1, "Collection log");
		in.addOwned(null, "Zulrah", "Magic fang", 2, "Collection log");
		in.addOwned(null, "Zulrah", "Coins", 100, "Collection log");
		return fx.planner.plan(in);
	}

	@Test
	public void theReviewSummarisesWhatIsTicked()
	{
		HistoryPlanner.Plan plan = plan();
		HistoryForm f = Swings.get(() -> new HistoryForm(plan));
		assertEquals("Will add: 2 kill counts and 3 owned items (2 lines)", f.summaryLabel().getText());
		Swings.edt(() -> f.tickKc(0, false));
		assertEquals("Will add: 1 kill count and 3 owned items (2 lines)", f.summaryLabel().getText());
		Swings.edt(() -> f.tickItem(0, false));
		assertTrue(f.summaryLabel().getText(), f.summaryLabel().getText().contains("(1 line)"));
		Swings.edt(() ->
		{
			f.tickKc(1, false);
			f.tickItem(1, false);
		});
		assertEquals("Nothing is ticked.", f.summaryLabel().getText());
		assertTrue(plan.isEmpty());
	}

	@Test
	public void theReviewMentionsWhatWasLeftOut()
	{
		HistoryForm f = Swings.get(() -> new HistoryForm(plan()));
		String t = Swings.text(f);
		assertTrue(t, t.contains("Not used: Goblin 3"));
		assertTrue(t, t.contains("not tracked here: Coins"));
		assertTrue(t, t.contains("Read from: Boss Kill Log, Collection log"));
		assertTrue(t, t.contains("does not record which kill each old drop came on"));
	}

	@Test
	public void tickAllTicksOnlyWhatWouldChangeSomething()
	{
		fx.ledger.setKc("zulrah", 2000);
		HistoryPlanner.Plan plan = plan();
		// the ledger is already ahead of the game for Zulrah, so only Vorkath counts
		assertEquals(1, plan.kcToApply());
		HistoryForm f = Swings.get(() -> new HistoryForm(plan));
		javax.swing.JCheckBox all = Swings.first(f, javax.swing.JCheckBox.class, c -> "Tick all".equals(c.getText()));
		assertNotNull(all);
		Swings.edt(all::doClick);
		assertFalse(all.isSelected());
		assertEquals(0, plan.kcToApply());
		Swings.edt(all::doClick);
		assertTrue(all.isSelected());
		assertEquals(1, plan.kcToApply());
	}
}
