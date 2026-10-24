package com.dropluckledger.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.dropluckledger.ledger.Drop;
import com.dropluckledger.ledger.Ledger;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JLabel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SettingsPageTest
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

	private SettingsPage page()
	{
		fx.openSettings();
		SettingsPage sp = fx.panel.settingsPage();
		assertNotNull(sp);
		return sp;
	}

	private static long pictures(java.awt.Component root)
	{
		return Swings.get(() ->
		{
			long n = 0;
			for (JLabel l : Swings.all(root, JLabel.class))
			{
				if (l.getIcon() instanceof ItemIcon)
				{
					n++;
				}
			}
			return n;
		});
	}

	@Test
	public void itShowsTheLedgersSettings()
	{
		fx.ledger.updatePrefs(p ->
		{
			p.window = 80;
			p.rowi = 1;
			p.lq = 1;
			p.images = 0;
		});
		SettingsPage sp = page();
		assertEquals(80, sp.windowSpinner().getValue());
		assertTrue(sp.ringBox().isSelected());
		assertTrue(sp.questBox().isSelected());
		assertFalse(sp.picturesBox().isSelected());
	}

	@Test
	public void changingASettingIsSavedWithTheLedger()
	{
		SettingsPage sp = page();
		Swings.edt(() -> sp.windowSpinner().setValue(120));
		assertEquals(120, fx.ledger.prefs().window);
		Swings.edt(sp.ringBox()::doClick);
		assertEquals(1, fx.ledger.prefs().rowi);
		assertTrue("Legends' Quest starts ticked", sp.questBox().isSelected());
		Swings.edt(sp.questBox()::doClick);
		assertEquals(0, fx.ledger.prefs().lq);
		Swings.edt(sp.ringBox()::doClick);
		assertEquals(0, fx.ledger.prefs().rowi);
		fx.settle();
		// the page was not rebuilt under the player's hand
		assertSame(sp, fx.panel.settingsPage());
		assertEquals(120, sp.windowSpinner().getValue());
	}

	@Test
	public void theLuckWindowChangesTheBossFigures()
	{
		fx.ledger.setKc("zulrah", 300);
		fx.openBoss("zulrah");
		assertEquals(50, fx.panel.bossPage().lastView().window);
		Swings.edt(fx.panel.settingsLink()::doClick);
		fx.settle();
		Swings.edt(() -> fx.panel.settingsPage().windowSpinner().setValue(200));
		fx.settle();
		Swings.edt(fx.panel.backButton()::doClick);
		fx.settle();
		assertEquals("boss", fx.view());
		assertEquals(200, fx.panel.bossPage().lastView().window);
	}

	@Test
	public void theHistoryButtonAsksThePlugin()
	{
		SettingsPage sp = page();
		Swings.edt(Swings.button(sp, "Add my game history")::doClick);
		assertEquals(1, fx.host.planRequests);
	}

	@Test
	public void exportGoesToThePlugin()
	{
		SettingsPage sp = page();
		Swings.edt(Swings.button(sp, "Export")::doClick);
		assertEquals(1, fx.host.exports);
	}

	private Ledger backup()
	{
		Ledger b = new Ledger(fx.data, () -> 9L);
		b.setKc("vorkath", 50);
		b.logDrop("vorkath", "Dragonbone necklace", 20, 1, null, Drop.SRC_MANUAL);
		return b;
	}

	@Test
	public void importingABackupCanMergeIt()
	{
		fx.ledger.setKc("zulrah", 100);
		SettingsPage sp = page();
		fx.host.backupToImport = backup();
		fx.dialogs.chooseAnswer = 0;
		Swings.edt(Swings.button(sp, "Import")::doClick);
		assertEquals(1, fx.host.imports);
		assertTrue(fx.dialogs.last(), fx.dialogs.last().contains("1 boss and 1 drop"));
		assertEquals(50, fx.ledger.kc("vorkath"));
		assertEquals(100, fx.ledger.kc("zulrah"));
		assertEquals(1, fx.ledger.copyOf("vorkath").drops.size());
		assertTrue(fx.host.replaced.isEmpty());
		fx.settle();
		assertEquals("Backup merged: 1 drop added", fx.panel.noteTexts().get(0));
	}

	@Test
	public void importingABackupCanReplaceTheLedgerButAsksFirst()
	{
		SettingsPage sp = page();
		Ledger b = backup();
		fx.host.backupToImport = b;
		fx.dialogs.chooseAnswer = 1;
		fx.dialogs.confirmAnswer = false;
		Swings.edt(Swings.button(sp, "Import")::doClick);
		assertTrue(fx.host.replaced.isEmpty());
		fx.dialogs.confirmAnswer = true;
		Swings.edt(Swings.button(sp, "Import")::doClick);
		assertEquals(List.of(b), fx.host.replaced);
		fx.settle();
		assertEquals("Backup restored", fx.panel.noteTexts().get(0));
	}

	@Test
	public void cancellingAnImportChangesNothing()
	{
		SettingsPage sp = page();
		fx.host.backupToImport = backup();
		fx.dialogs.chooseAnswer = 2;
		Swings.edt(Swings.button(sp, "Import")::doClick);
		fx.dialogs.chooseAnswer = -1;
		Swings.edt(Swings.button(sp, "Import")::doClick);
		assertEquals(0, fx.ledger.kc("vorkath"));
		assertTrue(fx.host.replaced.isEmpty());
		assertTrue(fx.panel.noteTexts().isEmpty());
	}

	@Test
	public void eraseAsksFirstAndKeepsTheSettings()
	{
		fx.ledger.setKc("zulrah", 100);
		fx.ledger.updatePrefs(p ->
		{
			p.window = 77;
			p.images = 0;
		});
		SettingsPage sp = page();
		JButton erase = Swings.button(sp, "Erase everything");
		fx.dialogs.confirmAnswer = false;
		Swings.edt(erase::doClick);
		assertTrue(fx.host.replaced.isEmpty());
		assertEquals(0, fx.host.dropLogsErased);
		fx.dialogs.confirmAnswer = true;
		Swings.edt(erase::doClick);
		assertEquals(1, fx.host.replaced.size());
		assertEquals(1, fx.host.dropLogsErased);
		Ledger fresh = fx.host.replaced.get(0);
		assertEquals(0, fresh.kc("zulrah"));
		assertTrue(fresh.activeBosses().isEmpty());
		assertEquals(77, fresh.prefs().window);
		assertEquals(0, fresh.prefs().images);
		fx.settle();
		assertEquals("Ledger erased", fx.panel.noteTexts().get(0));
	}

	private static String title(Fold f)
	{
		return Swings.get(() -> ((JButton) f.header()).getText());
	}

	@Test
	public void theBossesToShowAreTickedOneByOneOrByGroup()
	{
		fx.ledger.setKc("zulrah", 100);
		fx.ledger.setKc("duke-sucellus", 20);
		SettingsPage sp = page();
		assertTrue(sp.bossBox("zulrah").isSelected());
		assertEquals("Showing every boss", sp.shownText());
		assertFalse(sp.showAllButton().isEnabled());
		Fold dt2 = sp.groupFold("Desert Treasure II");
		assertEquals("Desert Treasure II (4/4)", title(dt2));
		// untick everything, then tick only the Desert Treasure II bosses
		Swings.edt(sp.showNoneButton()::doClick);
		fx.settle();
		assertFalse(fx.ledger.shown("zulrah"));
		assertEquals("Showing 0 of 68 bosses", sp.shownText());
		assertFalse(sp.bossBox("duke-sucellus").isSelected());
		assertFalse(sp.showNoneButton().isEnabled());
		assertEquals("Desert Treasure II (0/4)", title(dt2));
		Swings.edt(Swings.button(dt2, "Tick these")::doClick);
		fx.settle();
		for (String id : new String[]{"duke-sucellus", "the-leviathan", "the-whisperer", "vardorvis"})
		{
			assertTrue(id, fx.ledger.shown(id));
			assertTrue(id, sp.bossBox(id).isSelected());
		}
		assertFalse(fx.ledger.shown("zulrah"));
		assertEquals("Showing 4 of 68 bosses", sp.shownText());
		assertEquals("Desert Treasure II (4/4)", title(dt2));
		// one at a time
		Swings.edt(sp.bossBox("the-whisperer")::doClick);
		fx.settle();
		assertFalse(fx.ledger.shown("the-whisperer"));
		assertEquals("Desert Treasure II (3/4)", title(dt2));
		assertSame("the page was not rebuilt under the player's hand", sp, fx.panel.settingsPage());
		// the overview lists only those, and the kills of the others are kept
		Swings.edt(fx.panel.backButton()::doClick);
		fx.settle();
		String t = fx.pageText();
		assertTrue(t, t.contains("1 boss tracked \u00b7 3 of 68 shown"));
		assertTrue(t, t.contains("Duke Sucellus"));
		assertFalse(t, t.contains("Zulrah"));
		assertFalse(t, t.contains("The Whisperer"));
		assertEquals(100, fx.ledger.kc("zulrah"));
		// and back
		fx.openSettings();
		Swings.edt(fx.panel.settingsPage().showAllButton()::doClick);
		fx.settle();
		assertTrue(fx.ledger.prefs().hiddenBosses.isEmpty());
		assertEquals("Showing every boss", fx.panel.settingsPage().shownText());
	}

	@Test
	public void eraseKeepsTheBossesToShow()
	{
		fx.ledger.setKc("zulrah", 100);
		fx.ledger.setShown(java.util.Collections.singletonList("vorkath"), false);
		SettingsPage sp = page();
		fx.dialogs.confirmAnswer = true;
		Swings.edt(Swings.button(sp, "Erase everything")::doClick);
		Ledger fresh = fx.host.replaced.get(0);
		assertEquals(java.util.Collections.singletonList("vorkath"), fresh.prefs().hiddenBosses);
	}

	@Test
	public void picturesCanBeTurnedOffAndOn()
	{
		fx.ledger.setKc("zulrah", 300);
		fx.openBoss("zulrah");
		assertTrue(pictures(fx.panel.bossPage()) > 3);
		// switch them off in the settings
		Swings.edt(fx.panel.settingsLink()::doClick);
		fx.settle();
		SettingsPage sp = fx.panel.settingsPage();
		Swings.edt(sp.picturesBox()::doClick);
		fx.settle();
		assertEquals(0, fx.ledger.prefs().images);
		assertSame("the settings page stays", sp, fx.panel.settingsPage());
		Swings.edt(fx.panel.backButton()::doClick);
		fx.settle();
		assertEquals("boss", fx.view());
		assertEquals(0, pictures(fx.panel.bossPage()));
		fx.openOverview();
		assertEquals(0, pictures(fx.panel.overviewPage()));
		// and back on
		Swings.edt(fx.panel.settingsLink()::doClick);
		fx.settle();
		Swings.edt(fx.panel.settingsPage().picturesBox()::doClick);
		fx.settle();
		Swings.edt(fx.panel.backButton()::doClick);
		fx.settle();
		fx.ledger.setKc("zulrah", 301);
		fx.openBoss("zulrah");
		assertTrue(pictures(fx.panel.bossPage()) > 3);
	}

	@Test
	public void switchingPicturesWhileOnABossPageRebuildsItInPlace()
	{
		fx.ledger.setKc("zulrah", 300);
		fx.openBoss("zulrah");
		BossPage before = fx.panel.bossPage();
		assertTrue(pictures(before) > 3);
		fx.ledger.updatePrefs(p -> p.images = 0);
		fx.settle();
		BossPage after = fx.panel.bossPage();
		assertEquals("zulrah", after.bossId());
		assertEquals(0, pictures(after));
		assertEquals("boss", fx.view());
	}
}
