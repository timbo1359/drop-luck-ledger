package com.dropluckledger.ui;

import com.dropluckledger.engine.Catalog;
import com.dropluckledger.engine.Data.Boss;
import com.dropluckledger.ledger.Ledger;
import com.dropluckledger.ledger.Prefs;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * Settings that change the figures, the bosses the overview shows, the game history, backups.
 */
@SuppressWarnings("serial")
final class SettingsPage extends Column
{
	private final Ctx ctx;
	private final Ledger ledger;
	private final JSpinner window = new JSpinner(new SpinnerNumberModel(50, 1, 5000, 1));
	private final JCheckBox rowi = check("Wearing a ring of wealth (i)");
	private final JCheckBox lq = check("Legends' Quest completed");
	private final JCheckBox images = check("Show item pictures");
	/**
	 * "Bosses to show": a box for each boss, and the bosses of each category with the fold that holds them.
	 */
	private final Map<String, JCheckBox> bossBoxes = new LinkedHashMap<>();
	private final Map<String, List<String>> groups = new LinkedHashMap<>();
	private final Map<String, Fold> groupFolds = new LinkedHashMap<>();
	private final JLabel shownCount = Look.muted("");
	private final JButton showAll = Look.button("Tick all");
	private final JButton showNone = Look.button("Untick all");
	private boolean updating;

	SettingsPage(Ctx ctx)
	{
		super(8);
		this.ctx = ctx;
		this.ledger = ctx.ledger();

		add(Look.label("Settings", Look.bold(), Look.TEXT));

		Column figures = new Column(4);
		figures.add(Look.wrapped("<b>Luck window</b><br>How many upcoming kills the \"next kills\" figures look at.", Look.PAGE_TEXT, Look.small(), Look.TEXT));
		figures.add(window);
		figures.add(rowi);
		figures.add(lq);
		figures.add(images);
		add(figures);

		add(bossesToShow());

		Column history = new Column(4);
		history.add(Look.wrapped("<b>Game history</b><br>Reads your kill counts and collection log from the game and shows you what it would add before it adds anything. "
			+ "Open your Boss Kill Log (Adventure Log) and your Collection Log pages in game first, so the plugin can read them.", Look.PAGE_TEXT, Look.small(), Look.TEXT));
		JButton h = Look.button("Add my game history…");
		h.addActionListener(e -> ctx.reviewHistory());
		history.add(h);
		add(history);

		Column backup = new Column(4);
		backup.add(Look.wrapped("<b>Backup</b><br>A backup is a file you can keep, move to another computer, or open in the Drop Luck Ledger web page.", Look.PAGE_TEXT, Look.small(), Look.TEXT));
		JPanel row = new JPanel(new java.awt.GridLayout(1, 2, 4, 0));
		row.setOpaque(false);
		JButton export = Look.button("Export…");
		export.addActionListener(e -> ctx.host().exportBackup(ctx.window()));
		JButton in = Look.button("Import…");
		in.addActionListener(e -> ctx.host().importBackup(ctx.window(), this::chooseImport));
		row.add(export);
		row.add(in);
		backup.add(row);
		add(backup);

		Column erase = new Column(4);
		erase.add(Look.wrapped("<b>Start over</b><br>Removes every kill count and drop from this account's ledger.", Look.PAGE_TEXT, Look.small(), Look.TEXT));
		JButton wipe = Look.button("Erase everything…");
		wipe.setForeground(Look.BAD);
		wipe.addActionListener(e -> wipe());
		erase.add(wipe);
		add(erase);

		add(Look.wrapped("Everything stays on this computer. The plugin makes no network requests; boss data was taken from the OSRS Wiki when the plugin was built.",
			Look.PAGE_TEXT, Look.small(), Look.MUTED));

		window.addChangeListener(e ->
		{
			if (!updating)
			{
				int v = (Integer) window.getValue();
				ledger.updatePrefs(p -> p.window = v);
			}
		});
		rowi.addActionListener(e ->
		{
			if (!updating)
			{
				boolean on = rowi.isSelected();
				ledger.updatePrefs(p -> p.rowi = on ? 1 : 0);
			}
		});
		lq.addActionListener(e ->
		{
			if (!updating)
			{
				boolean on = lq.isSelected();
				ledger.updatePrefs(p -> p.lq = on ? 1 : 0);
			}
		});
		images.addActionListener(e ->
		{
			if (!updating)
			{
				boolean on = images.isSelected();
				ledger.updatePrefs(p -> p.images = on ? 1 : 0);
			}
		});
		sync();
	}

	private static JCheckBox check(String text)
	{
		JCheckBox c = new JCheckBox(text);
		c.setFont(Look.small());
		c.setOpaque(false);
		return c;
	}

	/**
	 * Which bosses the overview lists: a box per boss, in a fold per category, with ways to tick or untick many at once.
	 */
	private Column bossesToShow()
	{
		Column show = new Column(4);
		show.add(Look.wrapped("<b>Bosses to show</b><br>The overview lists only the ticked bosses: their items in Biggest Spoons, My bosses, Shared drops "
			+ "and the boss list. Kills and drops at the others are still recorded.", Look.PAGE_TEXT, Look.small(), Look.TEXT));
		List<String> everyId = new ArrayList<>();
		for (Boss b : Catalog.ordered(ledger.data()))
		{
			everyId.add(b.id);
			groups.computeIfAbsent(b.cat, k -> new ArrayList<>()).add(b.id);
			JCheckBox box = check(b.name);
			box.addActionListener(e ->
			{
				if (!updating)
				{
					ledger.setShown(Collections.singletonList(b.id), box.isSelected());
				}
			});
			bossBoxes.put(b.id, box);
		}
		JPanel buttons = new JPanel(new java.awt.GridLayout(1, 2, 4, 0));
		buttons.setOpaque(false);
		showAll.addActionListener(e -> ledger.setShown(everyId, true));
		showNone.addActionListener(e -> ledger.setShown(everyId, false));
		buttons.add(showAll);
		buttons.add(showNone);
		show.add(buttons);
		show.add(shownCount);
		for (Map.Entry<String, List<String>> g : groups.entrySet())
		{
			List<String> ids = g.getValue();
			Column list = new Column(0);
			list.setBorder(Look.pad(0, 10, 2, 0));
			JPanel links = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
			links.setOpaque(false);
			JButton all = Look.link("Tick these");
			all.addActionListener(e -> ledger.setShown(ids, true));
			JButton none = Look.link("Untick these");
			none.addActionListener(e -> ledger.setShown(ids, false));
			links.add(all);
			links.add(javax.swing.Box.createHorizontalStrut(12));
			links.add(none);
			list.add(links);
			for (String id : ids)
			{
				list.add(bossBoxes.get(id));
			}
			Fold fold = new Fold(g.getKey(), false, null, list);
			groupFolds.put(g.getKey(), fold);
			show.add(fold);
		}
		return show;
	}

	/**
	 * Makes the controls show the ledger's settings.
	 */
	void sync()
	{
		Prefs p = ledger.prefs();
		updating = true;
		try
		{
			window.setValue(p.window);
			rowi.setSelected(p.rowi != 0);
			lq.setSelected(p.lq != 0);
			images.setSelected(p.images != 0);
			Set<String> hidden = new HashSet<>(p.hiddenBosses);
			for (Map.Entry<String, JCheckBox> e : bossBoxes.entrySet())
			{
				e.getValue().setSelected(!hidden.contains(e.getKey()));
			}
			int shown = 0;
			for (Map.Entry<String, List<String>> g : groups.entrySet())
			{
				int n = 0;
				for (String id : g.getValue())
				{
					n += hidden.contains(id) ? 0 : 1;
				}
				shown += n;
				groupFolds.get(g.getKey()).setTitle(g.getKey() + " (" + n + "/" + g.getValue().size() + ")");
			}
			int total = bossBoxes.size();
			shownCount.setText(shown == total ? "Showing every boss" : "Showing " + shown + " of " + total + " bosses");
			showAll.setEnabled(shown < total);
			showNone.setEnabled(shown > 0);
		}
		finally
		{
			updating = false;
		}
	}

	/**
	 * A backup was chosen: ask how to bring it in.
	 */
	private void chooseImport(Ledger backup)
	{
		int bosses = backup.activeBosses().size();
		int drops = 0;
		for (String id : backup.activeBosses())
		{
			drops += backup.copyOf(id).drops.size();
		}
		String what = "This backup has " + bosses + " boss" + (bosses == 1 ? "" : "es") + " and " + drops + " drop" + (drops == 1 ? "" : "s") + ".\n\n"
			+ "Merge adds what is missing from your ledger and keeps everything you have.\nReplace swaps your whole ledger for the backup.";
		int choice = ctx.dialogs().choose(ctx.window(), "Import a backup", what, "Merge into my ledger", "Replace my ledger", "Cancel");
		if (choice == 0)
		{
			int added = ledger.mergeFrom(backup);
			ctx.notice("Backup merged: " + added + " drop" + (added == 1 ? "" : "s") + " added");
		}
		else if (choice == 1)
		{
			if (ctx.dialogs().confirm(ctx.window(), "Replace my ledger", "Replace your whole ledger with the backup? What you have now is lost unless you export it first.", "Replace"))
			{
				ctx.host().replaceLedger(backup);
				ctx.notice("Backup restored");
			}
		}
	}

	private void wipe()
	{
		if (!ctx.dialogs().confirm(ctx.window(), "Erase everything",
			"This removes every kill count and drop from your ledger. Export a backup first if you might want them back.", "Erase everything"))
		{
			return;
		}
		Ledger fresh = new Ledger(ledger.data());
		Prefs keep = ledger.prefs();
		fresh.updatePrefs(p ->
		{
			p.window = keep.window;
			p.rowi = keep.rowi;
			p.lq = keep.lq;
			p.images = keep.images;
			p.hiddenBosses = keep.hiddenBosses;
		});
		ctx.host().replaceLedger(fresh);
		ctx.notice("Ledger erased");
	}

	// ---- for tests

	JSpinner windowSpinner()
	{
		return window;
	}

	JCheckBox ringBox()
	{
		return rowi;
	}

	JCheckBox questBox()
	{
		return lq;
	}

	JCheckBox picturesBox()
	{
		return images;
	}

	JCheckBox bossBox(String bossId)
	{
		return bossBoxes.get(bossId);
	}

	Fold groupFold(String category)
	{
		return groupFolds.get(category);
	}

	JButton showAllButton()
	{
		return showAll;
	}

	JButton showNoneButton()
	{
		return showNone;
	}

	String shownText()
	{
		return shownCount.getText();
	}
}
