package com.dropluckledger.ui;

import com.dropluckledger.ledger.Ledger;
import com.dropluckledger.ledger.Prefs;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * Settings that change the figures, the game history, backups.
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
}
