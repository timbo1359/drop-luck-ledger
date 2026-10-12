package com.dropluckledger.ui;

import com.dropluckledger.engine.Data.Boss;
import com.dropluckledger.engine.Data.Param;
import com.dropluckledger.ledger.BossState;
import com.dropluckledger.ledger.Drop;
import com.dropluckledger.ledger.Ledger;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.util.LinkBrowser;

/**
 * One boss: kill count, settings and the tabs (tracker, drop table, about).
 */
@SuppressWarnings("serial")
final class BossPage extends Column implements ItemCard.Actions
{
	static final String WIKI = "https://oldschool.runescape.wiki/w/";
	static final String TRACKER = "tracker";
	static final String TABLE = "table";
	static final String ABOUT = "about";
	private static final int SHOWN_DROPS = 30;
	private static final int SHOWN_ROWS = 80;
	private static final int MAX_KC = 100_000_000;

	private final Ctx ctx;
	private final Ledger ledger;
	private final Boss boss;
	private final String id;
	private final Jobs.Slot<Views.BossView> trackerJob;
	private final Jobs.Slot<Views.TableView> tableJob;
	private final Map<String, Jobs.Slot<Views.ChartData>> chartJobs = new HashMap<>();
	private final Map<String, Views.ChartData> charts = new HashMap<>();
	private final Set<String> open = new HashSet<>();
	private final Map<String, JComponent> controls = new LinkedHashMap<>();
	private final Map<String, JToggleButton> tabButtons = new LinkedHashMap<>();
	private final JSpinner kc = new JSpinner(new SpinnerNumberModel(0, 0, MAX_KC, 1));
	private final JComboBox<String> scope = new JComboBox<>();
	private final JLabel periods = Look.muted("");
	private final JButton merge = Look.button("Merge into one");
	private final JLabel settingsSummary;
	private final Fold settings;
	private final Column content = new Column(6);
	private final JTextField filter = new JTextField();
	private Column tableRows;
	private JLabel tableCount;
	private Views.TableView tableView;
	private Views.BossView last;
	private long chartsFor = -1;
	private boolean updating;
	private boolean allDrops;
	private String tab;

	BossPage(Ctx ctx, String id, boolean settingsOpen, java.util.function.Consumer<Boolean> settingsToggled)
	{
		super(8);
		this.ctx = ctx;
		this.ledger = ctx.ledger();
		this.id = id;
		this.boss = ledger.data().boss(id);
		this.trackerJob = ctx.jobs().slot();
		this.tableJob = ctx.jobs().slot();
		String saved = ledger.prefs().tab;
		this.tab = TABLE.equals(saved) || ABOUT.equals(saved) ? saved : TRACKER;
		filter.setFont(Look.small());
		filter.setToolTipText("Filter items");
		filter.putClientProperty("JTextField.placeholderText", "Filter items");
		filter.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				fillTable();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				fillTable();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				fillTable();
			}
		});

		add(head());
		add(killCount());

		Column paramBox = new Column(6);
		buildParams(paramBox);
		settingsSummary = Look.wrapped("", Look.PAGE_TEXT - 4, Look.small(), Look.MUTED);
		settings = new Fold("Boss settings", settingsOpen, settingsToggled, settingsSummary, paramBox);
		if (!boss.params.isEmpty())
		{
			add(settings);
		}
		add(tabs());
		add(content);
		syncControls();
		showTab();
	}

	String bossId()
	{
		return id;
	}

	String tab()
	{
		return tab;
	}

	// ------------------------------------------------------------------ the top of the page

	private JComponent head()
	{
		Column c = new Column(2);
		c.add(Look.wrapped("<b>" + Look.esc(boss.name) + "</b>", Look.PAGE_TEXT, Look.bold().deriveFont(java.awt.Font.BOLD, Look.bold().getSize2D() + 3f), Look.TEXT));
		Views.Confidence conf = Views.confidence(boss);
		java.awt.Color cc = conf.level.equals("high") ? Look.GOOD : conf.level.equals("medium") ? Look.WARN : Look.BAD;
		String counts = boss.kc.isEmpty() ? boss.name : String.join(", ", boss.kc);
		JPanel line = new JPanel(new BorderLayout(6, 0));
		line.setOpaque(false);
		JLabel chips = Look.label("<html>" + Look.esc(boss.cat) + " · " + Look.colored("Data: " + Look.esc(conf.level), cc) + "</html>", Look.small(), Look.MUTED);
		chips.setToolTipText(Look.tip("How completely the wiki drop data has been captured.<br>" + Look.dim("Kill counts the game shows for this boss: " + Look.esc(counts))));
		line.add(chips, BorderLayout.CENTER);
		JButton wiki = Look.link("Wiki");
		wiki.setToolTipText("Open the wiki page");
		wiki.addActionListener(e -> LinkBrowser.browse(WIKI + boss.wiki));
		line.add(wiki, BorderLayout.EAST);
		c.add(line);
		return c;
	}

	/**
	 * The kill count, its quick buttons and the way to log a drop, in two rows.
	 */
	private JComponent killCount()
	{
		Column c = new Column(4);
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.add(Look.label("Kill count", Look.body(), Look.TEXT), BorderLayout.WEST);
		row.add(kc, BorderLayout.CENTER);
		c.add(row);
		JPanel quick = new JPanel(new BorderLayout(4, 0));
		quick.setOpaque(false);
		JPanel adds = new JPanel(new GridLayout(1, 3, 3, 0));
		adds.setOpaque(false);
		for (int n : new int[] {10, 100, 1000})
		{
			JButton b = Look.button("+" + n);
			b.setMargin(new java.awt.Insets(2, 3, 2, 3));
			b.setToolTipText("Add " + n + " kills");
			b.addActionListener(e -> ledger.addKc(id, n));
			adds.add(b);
		}
		quick.add(adds, BorderLayout.WEST);
		JButton log = Look.button("Log a drop");
		log.setToolTipText("Record a drop you got, with the kill it came on");
		log.addActionListener(e -> logForm(null, null));
		quick.add(log, BorderLayout.CENTER);
		c.add(quick);
		kc.addChangeListener(e ->
		{
			if (!updating)
			{
				ledger.setKc(id, (Integer) kc.getValue());
			}
		});
		return c;
	}

	// ------------------------------------------------------------------ boss settings

	private void buildParams(Column box)
	{
		for (Param p : boss.params)
		{
			switch (p.type)
			{
				case "bool":
				{
					JCheckBox cb = new JCheckBox(Look.html(Look.PAGE_TEXT - 30, Look.esc(p.label)));
					cb.setFont(Look.small());
					cb.setOpaque(false);
					cb.addActionListener(e ->
					{
						if (!updating)
						{
							ledger.setParam(id, p.name, cb.isSelected() ? 1 : 0, scopeNew());
						}
					});
					controls.put(p.name, cb);
					box.add(cb);
					break;
				}
				case "int":
				{
					int lo = p.min == null ? 0 : (int) Math.round(p.min);
					int hi = p.max == null ? 1_000_000 : (int) Math.round(p.max);
					JSpinner s = new JSpinner(new SpinnerNumberModel(Math.max(lo, Math.min(hi, intOf(p.def, 0))), lo, hi, 1));
					s.addChangeListener(e ->
					{
						if (!updating)
						{
							ledger.setParam(id, p.name, (Integer) s.getValue(), scopeNew());
						}
					});
					controls.put(p.name, s);
					box.add(captioned(p.label, s));
					break;
				}
				default:
				{
					JComboBox<String> cb = new JComboBox<>(p.options.toArray(new String[0]));
					cb.setFont(Look.small());
					cb.addActionListener(e ->
					{
						if (!updating && cb.getSelectedItem() != null)
						{
							ledger.setParam(id, p.name, cb.getSelectedItem().toString(), scopeNew());
						}
					});
					controls.put(p.name, cb);
					box.add(captioned(p.label, cb));
					break;
				}
			}
		}
		box.add(Look.muted("Apply changes to"));
		scope.setFont(Look.small());
		scope.addActionListener(e ->
		{
			if (!updating)
			{
				ctx.remember(id, tab, scopeKey());
			}
		});
		box.add(scope);
		box.add(periods);
		merge.addActionListener(e -> ledger.collapsePeriods(id));
		JPanel m = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		m.setOpaque(false);
		m.add(merge);
		box.add(m);
	}

	private static JPanel captioned(String caption, JComponent c)
	{
		JPanel p = new JPanel(new BorderLayout(0, 2));
		p.setOpaque(false);
		p.add(Look.wrapped(Look.esc(caption), Look.PAGE_TEXT - 4, Look.small(), Look.MUTED), BorderLayout.NORTH);
		p.add(c, BorderLayout.CENTER);
		return p;
	}

	private boolean scopeNew()
	{
		return scope.getSelectedIndex() == 1;
	}

	private String scopeKey()
	{
		return scopeNew() ? "new" : "all";
	}

	private static boolean truthy(Object v)
	{
		if (v instanceof Boolean)
		{
			return (Boolean) v;
		}
		if (v instanceof Number)
		{
			return ((Number) v).doubleValue() != 0;
		}
		return v != null && !v.toString().isEmpty();
	}

	private static int intOf(Object v, int fallback)
	{
		return v instanceof Number ? ((Number) v).intValue() : fallback;
	}

	/**
	 * Makes the controls show what the ledger has.
	 */
	void syncControls()
	{
		updating = true;
		try
		{
			kc.setValue(Math.max(0, Math.min(MAX_KC, ledger.kc(id))));
			Map<String, Object> vals = ledger.currentParams(id);
			for (Param p : boss.params)
			{
				JComponent c = controls.get(p.name);
				Object v = vals.get(p.name);
				if (c instanceof JCheckBox)
				{
					((JCheckBox) c).setSelected(truthy(v));
				}
				else if (c instanceof JSpinner)
				{
					SpinnerNumberModel m = (SpinnerNumberModel) ((JSpinner) c).getModel();
					int lo = ((Number) m.getMinimum()).intValue();
					int hi = ((Number) m.getMaximum()).intValue();
					((JSpinner) c).setValue(Math.max(lo, Math.min(hi, intOf(v, intOf(p.def, lo)))));
				}
				else if (c instanceof JComboBox && v != null)
				{
					@SuppressWarnings("unchecked")
					JComboBox<String> cb = (JComboBox<String>) c;
					cb.setSelectedItem(v.toString());
				}
			}
			BossState st = ledger.copyOf(id);
			int keep = ledger.prefs().scope.equals("new") ? 1 : 0;
			DefaultComboBoxModel<String> m = new DefaultComboBoxModel<>();
			m.addElement("all my kills");
			m.addElement("new kills only (from kill " + Fmt.num(st.kc + 1) + ")");
			scope.setModel(m);
			scope.setSelectedIndex(keep);
			boolean several = st.segs.size() > 1;
			periods.setText(several ? st.segs.size() + " setting periods" : " ");
			periods.setVisible(several);
			merge.setVisible(several);
			settingsSummary.setText(Look.html(Look.PAGE_TEXT - 4, Look.esc(summary(vals))));
		}
		finally
		{
			updating = false;
		}
	}

	private String summary(Map<String, Object> vals)
	{
		List<String> out = new ArrayList<>();
		for (Param p : boss.params)
		{
			Object v = vals.get(p.name);
			if (p.type.equals("bool"))
			{
				if (truthy(v))
				{
					out.add(p.label.replaceAll(" \\(.*\\)", ""));
				}
			}
			else
			{
				String value = v instanceof Number ? Fmt.num(((Number) v).doubleValue()) : String.valueOf(v);
				out.add(p.label.replaceAll(" \\(.*\\)", "") + ": " + value);
			}
		}
		if (out.isEmpty())
		{
			return "defaults";
		}
		String s = String.join(" · ", out);
		return s.length() > 90 ? s.substring(0, 90) : s;
	}

	// ------------------------------------------------------------------ tabs

	private JComponent tabs()
	{
		JPanel p = new JPanel(new GridLayout(1, 3, 2, 0));
		p.setOpaque(false);
		ButtonGroup g = new ButtonGroup();
		String[][] defs = {{TRACKER, "Tracker"}, {TABLE, "Drop table"}, {ABOUT, "About"}};
		for (String[] d : defs)
		{
			JToggleButton b = new JToggleButton(d[1]);
			b.setFont(Look.small());
			b.setFocusPainted(false);
			b.setMargin(new java.awt.Insets(2, 2, 2, 2));
			b.setSelected(d[0].equals(tab));
			b.addActionListener(e -> selectTab(d[0]));
			g.add(b);
			p.add(b);
			tabButtons.put(d[0], b);
		}
		return p;
	}

	void selectTab(String key)
	{
		if (key.equals(tab) && content.getComponentCount() > 0)
		{
			return;
		}
		tab = key;
		tabButtons.get(key).setSelected(true);
		ctx.remember(id, tab, scopeKey());
		showTab();
	}

	private void showTab()
	{
		switch (tab)
		{
			case TABLE:
				trackerJob.cancel();
				if (tableView != null)
				{
					showTable(tableView);
				}
				else
				{
					Swap.into(content, java.util.Collections.<Component>singletonList(working()));
				}
				tableJob.run(c -> ctx.analyzer().table(id), this::showTable);
				break;
			case ABOUT:
				trackerJob.cancel();
				tableJob.cancel();
				Swap.into(content, about());
				break;
			default:
				tableJob.cancel();
				if (last != null)
				{
					showTracker(last);
				}
				else
				{
					Swap.into(content, java.util.Collections.<Component>singletonList(working()));
				}
				trackerJob.run(c -> ctx.analyzer().boss(id, c), this::showTracker);
				break;
		}
	}

	private static JComponent working()
	{
		return Look.wrapped("Working it out…", Look.PAGE_TEXT, Look.small(), Look.MUTED);
	}

	/**
	 * The ledger changed: bring the controls and the open tab up to date.
	 */
	void refresh()
	{
		syncControls();
		if (TRACKER.equals(tab))
		{
			trackerJob.run(c -> ctx.analyzer().boss(id, c), this::showTracker);
		}
		else if (TABLE.equals(tab))
		{
			tableJob.run(c -> ctx.analyzer().table(id), this::showTable);
		}
	}

	// ------------------------------------------------------------------ the tracker tab

	private void showTracker(Views.BossView v)
	{
		if (v.rev != chartsFor)
		{
			charts.clear();
			chartsFor = v.rev;
		}
		last = v;
		if (!TRACKER.equals(tab))
		{
			return;
		}
		List<Component> parts = new ArrayList<>();
		if (v.kc == 0 && v.state.drops.isEmpty())
		{
			parts.add(note("No kills yet. Kills and drops you get in game are recorded automatically; set your kill count above if you already have some."));
		}
		if (v.any != null)
		{
			parts.add(new AnyCard(v.any));
		}
		for (Views.ItemView iv : v.items)
		{
			parts.add(new ItemCard(ctx, iv, this));
		}
		parts.add(ownedRow());
		if (!v.addable.isEmpty())
		{
			parts.add(alsoTrack(v.addable));
		}
		parts.add(history(v));
		Swap.into(content, parts);
	}

	private static JComponent note(String text)
	{
		JPanel p = new JPanel(new BorderLayout());
		p.setBackground(Look.CARD);
		p.setBorder(Look.card());
		p.add(Look.wrapped(Look.esc(text), Look.CARD_TEXT, Look.small(), Look.MUTED));
		return p;
	}

	private JComponent ownedRow()
	{
		JButton b = Look.button("Items I already own…");
		b.setToolTipText(Look.tip("Starting out with items you got before you began tracking? Add them here, or use \"Add my game history\" in Settings."));
		b.addActionListener(e -> ownedForm());
		return b;
	}

	private JComponent alsoTrack(List<String> names)
	{
		JPanel c = new JPanel(new BorderLayout(6, 0));
		c.setOpaque(false);
		c.add(Look.muted("Also track"), BorderLayout.WEST);
		List<String> all = new ArrayList<>();
		all.add("Choose an item…");
		all.addAll(names);
		JComboBox<String> box = new JComboBox<>(all.toArray(new String[0]));
		box.setFont(Look.small());
		box.setMaximumRowCount(14);
		box.setToolTipText(Look.tip("Everything on the drop table can be tracked; the main collection log items are shown by default."));
		box.addActionListener(e ->
		{
			if (box.getSelectedIndex() > 0)
			{
				ledger.setTracked(id, (String) box.getSelectedItem(), true);
			}
		});
		c.add(box, BorderLayout.CENTER);
		return c;
	}

	private JComponent history(Views.BossView v)
	{
		Column c = new Column(4);
		List<Drop> drops = new ArrayList<>(v.state.drops);
		drops.sort(Comparator.<Drop>comparingInt(d -> d.kc == null ? 0 : d.kc).reversed().thenComparing(Comparator.<Drop>comparingLong(d -> d.t).reversed()));
		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.add(Look.label("Drop history", Look.bold(), Look.TEXT), BorderLayout.WEST);
		top.add(Look.muted(drops.size() + " logged"), BorderLayout.EAST);
		c.add(top);
		if (drops.isEmpty())
		{
			c.add(Look.wrapped("Nothing logged yet. Drops are recorded as you get them, or use \"Log a drop\".", Look.PAGE_TEXT, Look.small(), Look.MUTED));
			return c;
		}
		int shown = allDrops ? drops.size() : Math.min(SHOWN_DROPS, drops.size());
		for (int i = 0; i < shown; i++)
		{
			c.add(dropRow(drops.get(i)));
		}
		if (shown < drops.size())
		{
			JButton more = Look.link("Show all " + drops.size());
			more.addActionListener(e ->
			{
				allDrops = true;
				if (last != null)
				{
					showTracker(last);
				}
			});
			c.add(more);
		}
		return c;
	}

	private JComponent dropRow(Drop d)
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(Look.CARD);
		row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Look.BG, 1), Look.pad(3, 5, 3, 3)));
		JLabel name = new JLabel(Look.html(112, Look.esc(d.item) + (d.qty > 1 ? " ×" + Fmt.num(d.qty) : "") + "<br><font color='"
			+ Look.hex(Look.MUTED) + "'>" + (d.kc == null ? "kill unknown" : "kill " + Fmt.num(d.kc))
			+ (Drop.SRC_IMPORT.equals(d.src) ? " · from history" : Drop.SRC_LIVE.equals(d.src) ? " · seen in game" : "") + "</font>"));
		name.setFont(Look.small());
		name.setForeground(Look.TEXT);
		name.setToolTipText(d.kc == null ? "Added without a kill number (from game history, or marked as already owned)" : null);
		ItemIcon.set(name, ctx.icons(), d.item, ctx.pictures(), 24, 22);
		row.add(name, BorderLayout.CENTER);
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
		buttons.setOpaque(false);
		JButton edit = Look.button("Edit");
		edit.addActionListener(e -> logForm(null, d));
		JButton del = new JButton(Glyph.of(Glyph.Kind.CROSS, Look.BAD));
		del.setMargin(new java.awt.Insets(2, 4, 2, 4));
		del.setToolTipText("Delete this drop");
		del.addActionListener(e -> deleteDrop(d));
		buttons.add(edit);
		buttons.add(del);
		row.add(buttons, BorderLayout.EAST);
		return row;
	}

	private void deleteDrop(Drop d)
	{
		String what = d.item + (d.kc != null ? " at kill " + Fmt.num(d.kc) : "");
		if (ctx.dialogs().confirm(ctx.window(), "Delete drop", "Delete " + what + " from your history?", "Delete"))
		{
			ledger.deleteDrop(id, d.id);
		}
	}

	// ------------------------------------------------------------------ item card actions

	@Override
	public void log(String item)
	{
		logForm(item, null);
	}

	@Override
	public void hide(String item)
	{
		ledger.setTracked(id, item, false);
	}

	@Override
	public void toggle(String item)
	{
		if (!open.remove(item))
		{
			open.add(item);
		}
		if (last != null)
		{
			showTracker(last);
		}
	}

	@Override
	public boolean isOpen(String item)
	{
		return open.contains(item);
	}

	@Override
	public void chart(String item, java.util.function.Consumer<Views.ChartData> into)
	{
		Views.ChartData known = charts.get(item);
		if (known != null)
		{
			into.accept(known);
			return;
		}
		Jobs.Slot<Views.ChartData> slot = chartJobs.computeIfAbsent(item, k -> ctx.jobs().slot());
		slot.run(c -> ctx.analyzer().chart(id, item), d ->
		{
			charts.put(item, d);
			if (last != null && TRACKER.equals(tab))
			{
				showTracker(last);
			}
		});
	}

	// ------------------------------------------------------------------ forms

	void logForm(String item, Drop edit)
	{
		LogForm form = new LogForm(ledger, ctx.analyzer(), id, edit, item);
		String title = (edit != null ? "Edit drop" : "Log a drop") + " · " + boss.name;
		while (true)
		{
			if (!ctx.dialogs().form(ctx.window(), title, form, edit != null ? "Save changes" : "Log drop"))
			{
				return;
			}
			String problem = form.problem();
			if (problem == null)
			{
				break;
			}
			ctx.dialogs().note(ctx.window(), title, problem);
		}
		ctx.notice("Logged " + form.save());
	}

	void ownedForm()
	{
		OwnedForm form = new OwnedForm(ctx, id);
		if (ctx.dialogs().form(ctx.window(), "Items I already own · " + boss.name, form, "Save"))
		{
			int n = form.save();
			ctx.notice(n > 0 ? "Added " + n + " owned item" + (n == 1 ? "" : "s") : "No changes");
		}
	}

	// ------------------------------------------------------------------ the drop table tab

	private void showTable(Views.TableView t)
	{
		tableView = t;
		if (!TABLE.equals(tab))
		{
			return;
		}
		Column top = new Column(4);
		top.add(Look.wrapped("Per-kill chance with your current settings", Look.PAGE_TEXT, Look.small(), Look.MUTED));
		if (!t.always.isEmpty())
		{
			top.add(Look.wrapped("<b>Always:</b> " + Look.esc(String.join(" · ", t.always)), Look.PAGE_TEXT, Look.small(), Look.TEXT));
		}
		top.add(filter);
		tableCount = Look.muted("");
		top.add(tableCount);
		tableRows = new Column(2);
		List<Component> parts = new ArrayList<>();
		parts.add(top);
		parts.add(tableRows);
		parts.add(Look.wrapped("\"Per kill\" combines every roll that can produce the item (rolls per kill, pre-rolls, shared tables, tertiaries) for your settings."
			+ " \"As printed\" is the rate on the wiki's table for a single roll.", Look.PAGE_TEXT, Look.small(), Look.MUTED));
		fillTable();
		Swap.into(content, parts);
	}

	private void fillTable()
	{
		if (tableView == null || tableRows == null)
		{
			return;
		}
		String q = filter.getText().trim().toLowerCase();
		List<Views.TableRow> rows = new ArrayList<>();
		for (Views.TableRow r : tableView.rows)
		{
			if (q.isEmpty() || r.name.toLowerCase().contains(q))
			{
				rows.add(r);
			}
		}
		tableCount.setText(rows.size() + " item" + (rows.size() == 1 ? "" : "s") + (rows.size() > SHOWN_ROWS ? " (showing the first " + SHOWN_ROWS + ", type to find others)" : ""));
		tableRows.removeAll();
		for (int i = 0; i < Math.min(SHOWN_ROWS, rows.size()); i++)
		{
			Views.TableRow r = rows.get(i);
			JPanel row = new JPanel(new BorderLayout(4, 0));
			row.setBackground(Look.CARD);
			row.setBorder(Look.pad(3, 5, 3, 5));
			JLabel name = new JLabel(Look.html(118, (r.notable ? "<b>" : "") + Look.esc(r.name) + (r.notable ? "</b>" : "")
				+ (r.log ? " " + Look.colored("log", Look.GOLD) : "") + "<br><font color='" + Look.hex(Look.MUTED) + "'>"
				+ Look.esc((r.qty.isEmpty() ? "" : r.qty + " · ") + String.join(", ", r.printed)) + "</font>"));
			name.setFont(Look.small());
			name.setForeground(Look.TEXT);
			name.setToolTipText(r.where.isEmpty() ? null : String.join("; ", r.where.subList(0, Math.min(2, r.where.size()))));
			ItemIcon.set(name, ctx.icons(), r.name, ctx.pictures(), 24, 22);
			row.add(name, BorderLayout.CENTER);
			JLabel p = Look.label(Fmt.rate(r.p), Look.small(), Look.TEXT);
			row.add(p, BorderLayout.EAST);
			tableRows.add(row);
		}
		tableRows.revalidate();
		tableRows.repaint();
	}

	// ------------------------------------------------------------------ the about tab

	private List<Component> about()
	{
		List<Component> parts = new ArrayList<>();
		Views.Confidence conf = Views.confidence(boss);
		StringBuilder notes = new StringBuilder();
		for (String n : boss.notes)
		{
			if (!n.startsWith("PASS-1"))
			{
				notes.append("• ").append(Look.esc(n)).append("<br>");
			}
		}
		parts.add(Look.wrapped("<b>How " + Look.esc(boss.name) + " works</b>", Look.PAGE_TEXT, Look.body(), Look.TEXT));
		parts.add(Look.wrapped(notes.toString(), Look.PAGE_TEXT, Look.small(), Look.TEXT));
		List<String> feats = features();
		if (!feats.isEmpty())
		{
			StringBuilder f = new StringBuilder("<b>Special mechanics handled</b><br>");
			for (String s : feats)
			{
				f.append("• ").append(Look.esc(s)).append("<br>");
			}
			parts.add(Look.wrapped(f.toString(), Look.PAGE_TEXT, Look.small(), Look.TEXT));
		}
		StringBuilder c = new StringBuilder("<b>Data confidence: " + Look.esc(conf.level) + "</b><br>Sample data: "
			+ Look.esc(boss.samples == null || boss.samples.isEmpty() ? "not stated on the wiki" : boss.samples) + ".");
		if (!conf.unknown.isEmpty())
		{
			c.append("<br><b>Rate not captured:</b> ").append(Look.esc(String.join(", ", conf.unknown))).append(" (shown, but left out of the luck maths).");
		}
		if (!conf.inferred.isEmpty())
		{
			c.append("<br><b>Reconstructed rows:</b> ").append(Look.esc(String.join(", ", conf.inferred.subList(0, Math.min(12, conf.inferred.size())))))
				.append(conf.inferred.size() > 12 ? "…" : "").append(".");
		}
		if (!conf.gaps.isEmpty())
		{
			c.append("<br><b>Known gaps:</b>");
			for (String g : conf.gaps)
			{
				c.append("<br>• ").append(Look.esc(g));
			}
		}
		if (conf.level.equals("high"))
		{
			c.append("<br>Every table was read from the wiki twice and sums to 100%.");
		}
		parts.add(Look.wrapped(c.toString(), Look.PAGE_TEXT, Look.small(), Look.TEXT));
		JButton wiki = Look.link("Open the wiki page");
		wiki.addActionListener(e -> LinkBrowser.browse(WIKI + boss.wiki));
		JPanel w = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		w.setOpaque(false);
		w.add(wiki);
		parts.add(w);
		return parts;
	}

	private List<String> features()
	{
		List<String> feats = new ArrayList<>();
		Map<String, com.dropluckledger.engine.Engine.ItemMeta> m = boss.meta();
		boolean seq = false;
		boolean pity = false;
		boolean shared = false;
		for (com.dropluckledger.engine.Engine.ItemMeta im : m.values())
		{
			seq |= im.seq != null;
			pity |= im.pity > 0;
			shared |= ctx.analyzer().shared(im.name) && boss.logSet().contains(im.name);
		}
		boolean nodup = false;
		boolean follow = false;
		for (com.dropluckledger.engine.Data.Table t : boss.tables)
		{
			follow |= t.opts.get("follow") != null && !t.opts.get("follow").isEmpty();
			for (com.dropluckledger.engine.Data.Row r : t.items)
			{
				nodup |= r.flagged("nodup");
			}
		}
		if (seq)
		{
			feats.add("Sequence drops (the same roll hands out different items on successive hits)");
		}
		if (pity)
		{
			feats.add("Pity: a guaranteed drop after a number of kills");
		}
		if (nodup)
		{
			feats.add("No-duplicate sets (pieces you own are skipped until the set is complete)");
		}
		if (shared)
		{
			feats.add("Shared items that other bosses also drop (pooled luck is shown once you track those bosses)");
		}
		if (follow)
		{
			feats.add("Follow-up rolls (an item you receive gives another roll somewhere else)");
		}
		return feats;
	}

	// ------------------------------------------------------------------ for tests

	JSpinner killSpinner()
	{
		return kc;
	}

	Map<String, JComponent> paramControls()
	{
		return controls;
	}

	JComboBox<String> scopeBox()
	{
		return scope;
	}

	Column contentColumn()
	{
		return content;
	}

	Views.BossView lastView()
	{
		return last;
	}

	JTextField filterField()
	{
		return filter;
	}
}
