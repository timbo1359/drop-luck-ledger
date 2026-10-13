package com.dropluckledger.ui;

import com.dropluckledger.engine.Catalog;
import com.dropluckledger.engine.Data.Boss;
import com.dropluckledger.engine.Engine;
import com.dropluckledger.engine.Luck;
import com.dropluckledger.ledger.Ledger;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * The first page: where the player's luck stands across every boss, and the way into each boss.
 */
@SuppressWarnings("serial")
final class OverviewPage extends Column
{
	static final String SPOONS = "Biggest Spoons";
	/**
	 * Spoons listed before "Show all".
	 */
	static final int SPOONS_SHOWN = 10;

	private final Ctx ctx;
	private final Ledger ledger;
	private final Jobs.Slot<Views.OverviewView> job;
	private final JTextField search = new JTextField();
	private final Column results = new Column(8);
	private final Map<String, Boolean> openGroups = new LinkedHashMap<>();
	private boolean openHelp;
	private boolean allSpoons;
	private Views.OverviewView view;

	OverviewPage(Ctx ctx)
	{
		super(8);
		this.ctx = ctx;
		this.ledger = ctx.ledger();
		this.job = ctx.jobs().slot();
		search.setFont(Look.small());
		search.setToolTipText("Find a boss, or an item a boss drops");
		search.putClientProperty("JTextField.placeholderText", "Find a boss or an item");
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				build();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				build();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				build();
			}
		});
		add(Look.label("Your drop luck", Look.bold(), Look.TEXT));
		add(search);
		add(results);
		build();
		refresh();
	}

	/**
	 * The ledger changed: work the figures out again.
	 */
	void refresh()
	{
		job.run(c -> ctx.analyzer().overview(c), v ->
		{
			view = v;
			build();
		});
	}

	JTextField searchField()
	{
		return search;
	}

	Views.OverviewView lastView()
	{
		return view;
	}

	// ------------------------------------------------------------------ building

	private void build()
	{
		List<Component> parts = new ArrayList<>();
		String q = search.getText().trim().toLowerCase();
		if (!q.isEmpty())
		{
			parts.addAll(found(q));
			Swap.into(results, parts);
			return;
		}
		if (view == null)
		{
			parts.add(Look.wrapped("Working it out…", Look.PAGE_TEXT, Look.small(), Look.MUTED));
		}
		else if (view.active == 0)
		{
			parts.add(empty());
		}
		else
		{
			parts.add(Look.muted(counts(view)));
			if (view.tracked == 0)
			{
				parts.add(noneShown());
			}
			else
			{
				parts.add(spoons(view));
				parts.add(myBosses(view));
				if (!view.pooled.isEmpty())
				{
					parts.add(shared(view));
				}
			}
		}
		parts.add(allBosses());
		parts.add(help());
		Swap.into(results, parts);
	}

	/**
	 * "4 bosses tracked · 64 available", or with bosses left out under Settings "4 bosses tracked · 4 of 64 shown".
	 */
	private String counts(Views.OverviewView v)
	{
		int total = ledger.data().bosses.size();
		int hidden = ledger.prefs().hiddenBosses.size();
		String tracked = v.tracked + " boss" + (v.tracked == 1 ? "" : "es") + " tracked · ";
		return tracked + (hidden == 0 ? total + " available" : (total - hidden) + " of " + total + " shown");
	}

	/**
	 * The player has kills, but none at the bosses ticked to show.
	 */
	private JComponent noneShown()
	{
		Column c = new Column(6);
		c.add(Look.wrapped("None of the bosses you have kills at are ticked under Settings, Bosses to show. Their kills and drops are still recorded.",
			Look.PAGE_TEXT, Look.small(), Look.MUTED));
		JButton b = Look.button("Choose bosses…");
		b.addActionListener(e -> ctx.openSettings());
		c.add(b);
		return c;
	}

	private JComponent empty()
	{
		Column c = new Column(6);
		c.add(Look.wrapped("Nothing tracked yet. Kills and drops you get in game are recorded automatically from now on. "
			+ "To bring in what you already have, add your game history, or pick a boss below and set its kill count.", Look.PAGE_TEXT, Look.small(), Look.MUTED));
		JButton b = Look.button("Add my game history…");
		b.setToolTipText("Reads your kill counts and collection log from the game and shows you what it would add");
		b.addActionListener(e -> ctx.reviewHistory());
		c.add(b);
		return c;
	}

	/**
	 * A heading whose explanation is in a tooltip, so it takes no room on the page.
	 */
	private JComponent heading(String title, String help)
	{
		JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		p.setOpaque(false);
		p.add(Look.label(title, Look.bold(), Look.TEXT));
		if (help != null)
		{
			JLabel q = Look.label("(?)", Look.small(), Look.MUTED);
			q.setToolTipText(Look.tip(Look.esc(help)));
			p.setToolTipText(Look.tip(Look.esc(help)));
			p.add(q);
		}
		return p;
	}

	/**
	 * The items the player has been luckiest with: the first few, and the rest on request.
	 */
	private JComponent spoons(Views.OverviewView v)
	{
		Column c = new Column(3);
		c.add(heading(SPOONS, "The items you have been luckiest with, luckiest first: copies that came ahead of the drop rate. Under each item: the boss, "
			+ "and the copies you got against the copies your kills would give on average. The percentage is the chance, from all your kills, that you "
			+ "would have the next copy by now, so a low figure means you are well ahead. Hover a line for the figures; click to open the boss."));
		List<Views.Rank> rows = v.spoons;
		if (rows.isEmpty())
		{
			c.add(Look.wrapped("No spoons yet. Items you get ahead of the drop rate show up here.", Look.PAGE_TEXT, Look.small(), Look.MUTED));
			return c;
		}
		int shown = allSpoons ? rows.size() : Math.min(SPOONS_SHOWN, rows.size());
		for (int i = 0; i < shown; i++)
		{
			c.add(rankRow(rows.get(i)));
		}
		if (rows.size() > SPOONS_SHOWN)
		{
			JButton more = Look.link(allSpoons ? "Show fewer" : "Show all " + rows.size());
			more.addActionListener(e ->
			{
				allSpoons = !allSpoons;
				build();
			});
			JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
			p.setOpaque(false);
			p.add(more);
			c.add(p);
		}
		return c;
	}

	private JComponent rankRow(Views.Rank r)
	{
		Color color = Look.luck(r.label.key);
		Color bar = Look.chance(r.chance);
		JPanel row = new JPanel(new BorderLayout(6, 2));
		row.setBackground(Look.CARD);
		row.setBorder(Look.pad(3, 6, 3, 6));
		// how big the spoon is: the copies received against the copies expected from the kills made
		JLabel name = new JLabel(Look.html(126, Look.esc(r.item) + "<br>" + Look.dim(Look.esc(r.where) + " · " + r.got + " of " + Fmt.expected(r.expected))));
		name.setFont(Look.small());
		name.setForeground(Look.TEXT);
		ItemIcon.set(name, ctx.icons(), r.item, ctx.pictures(), 24, 22);
		row.add(name, BorderLayout.CENTER);
		row.add(figure(Fmt.pct(r.chance), bar, r.label.text, color), BorderLayout.EAST);
		Meter m = new Meter(3);
		m.set(r.chance, bar);
		row.add(m, BorderLayout.SOUTH);
		row.setToolTipText(Look.tip("<b>" + Look.esc(r.item) + "</b> " + Look.dim(Look.esc(r.where)) + "<br>" + (r.pooled ? "All these bosses together: " : "")
			+ r.got + " received vs " + Fmt.expected(r.expected) + " expected<br><b>" + Fmt.pct(r.chance) + "</b> chance of "
			+ (r.got > 0 ? "copy " + (r.got + 1) : "one") + " by now<br>" + Look.colored("<b>" + Look.esc(r.label.text) + "</b>", color)
			+ " · luckier than " + Fmt.pct(r.pct) + " of players with the same kills<br>" + Look.dim("Click to open")));
		Look.clickable(row, () -> ctx.openBoss(r.bossId));
		return row;
	}

	/**
	 * The figure at the right of a line: the chance on top and the luck word under it.
	 */
	private static JComponent figure(String chance, Color chanceColor, String word, Color wordColor)
	{
		JPanel p = new JPanel();
		p.setOpaque(false);
		p.setLayout(new javax.swing.BoxLayout(p, javax.swing.BoxLayout.Y_AXIS));
		JLabel top = Look.label(chance, Look.body(), chanceColor);
		top.setAlignmentX(Component.RIGHT_ALIGNMENT);
		p.add(top);
		JLabel bottom = Look.label(word, Look.small(), wordColor);
		bottom.setAlignmentX(Component.RIGHT_ALIGNMENT);
		p.add(bottom);
		return p;
	}

	private JComponent myBosses(Views.OverviewView v)
	{
		Column c = new Column(3);
		c.add(heading("My bosses", null));
		for (Views.BossRow b : v.bosses)
		{
			JPanel row = new JPanel(new BorderLayout(6, 0));
			row.setBackground(Look.CARD);
			row.setBorder(Look.pad(3, 6, 3, 6));
			StringBuilder t = new StringBuilder("<b>").append(Look.esc(b.name)).append("</b>");
			StringBuilder second = new StringBuilder();
			if (b.any != null && b.any.label != null && !"na".equals(b.any.label.key))
			{
				second.append(Look.colored(Look.esc(b.any.label.text), Look.luck(b.any.label.key))).append(Look.dim(" " + b.any.O + " of " + Fmt.expected(b.any.expected)));
			}
			if (b.total > 0)
			{
				second.append(second.length() > 0 ? Look.dim(" · ") : "").append(Look.dim("log " + b.got + "/" + b.total));
			}
			if (second.length() > 0)
			{
				t.append("<br>").append(second);
			}
			JLabel l = new JLabel(Look.html(150, t.toString()));
			l.setFont(Look.small());
			l.setForeground(Look.TEXT);
			row.add(l, BorderLayout.CENTER);
			row.add(Look.label(Fmt.num(b.kc), Look.body(), Look.TEXT), BorderLayout.EAST);
			StringBuilder tip = new StringBuilder("<b>").append(Look.esc(b.name)).append("</b> · ").append(Fmt.num(b.kc)).append(" kills");
			if (b.any != null)
			{
				tip.append("<br>Uniques together: ").append(b.any.O).append(" received vs ").append(Fmt.expected(b.any.expected)).append(" expected<br><b>")
					.append(Fmt.pct(b.any.another)).append("</b> chance of ").append(b.any.O > 0 ? "another" : "one").append(" by now");
				if (!"na".equals(b.any.label.key))
				{
					tip.append(" · ").append(Look.colored("<b>" + Look.esc(b.any.label.text) + "</b>", Look.luck(b.any.label.key))).append(" (luckier than ")
						.append(Fmt.pct(b.any.pct)).append(" of players)");
				}
			}
			if (b.total > 0)
			{
				tip.append("<br>Collection log items: ").append(b.got).append(" of ").append(b.total);
			}
			row.setToolTipText(Look.tip(tip.toString()));
			Look.clickable(row, () -> ctx.openBoss(b.id));
			c.add(row);
		}
		return c;
	}

	private JComponent shared(Views.OverviewView v)
	{
		Column c = new Column(3);
		c.add(heading("Shared drops", "Items several of your bosses can drop. Each boss has its own rate, so these combine all of those bosses' kills into one luck figure."));
		for (Luck.Pool p : v.pooled)
		{
			Luck.PoolPer main = p.per.get(0);
			StringBuilder per = new StringBuilder();
			for (Luck.PoolPer x : p.per)
			{
				if (x.K > main.K)
				{
					main = x;
				}
				if (per.length() > 0)
				{
					per.append(" · ");
				}
				per.append(x.name).append(' ').append(Fmt.num(x.K));
			}
			final String openId = main.id;
			boolean known = !Double.isNaN(p.pct) && !"na".equals(p.label.key);
			Color color = known ? Look.luck(p.label.key) : Look.MUTED;
			JPanel row = new JPanel(new BorderLayout(6, 2));
			row.setBackground(Look.CARD);
			row.setBorder(Look.pad(3, 6, 3, 6));
			JLabel name = new JLabel(Look.html(126, "<b>" + Look.esc(p.name) + "</b><br>" + Look.dim(p.per.size() + " bosses · " + p.O + " vs " + Fmt.expected(p.expected))));
			name.setFont(Look.small());
			name.setForeground(Look.TEXT);
			ItemIcon.set(name, ctx.icons(), p.name, ctx.pictures(), 24, 22);
			row.add(name, BorderLayout.CENTER);
			Color bar = known ? Look.chance(p.another) : Look.MUTED;
			row.add(figure(known ? Fmt.pct(p.another) : "—", bar, known ? p.label.text : "no kills", color), BorderLayout.EAST);
			Meter m = new Meter(3);
			m.set(known ? p.another : 0, bar);
			row.add(m, BorderLayout.SOUTH);
			row.setToolTipText(Look.tip("<b>" + Look.esc(p.name) + "</b><br>" + Look.esc(per.toString()) + " kills<br>" + p.O + " received vs " + Fmt.expected(p.expected)
				+ " expected" + (known ? "<br><b>" + Fmt.pct(p.another) + "</b> chance of " + (p.O > 0 ? "another" : "one") + " by now<br>"
				+ Look.colored("<b>" + Look.esc(p.label.text) + "</b>", color) + " · luckier than " + Fmt.pct(p.pct) + " of players" : "")
				+ "<br>" + Look.dim("Click to open")));
			Look.clickable(row, () -> ctx.openBoss(openId));
			c.add(row);
		}
		return c;
	}

	private JComponent allBosses()
	{
		Column c = new Column(4);
		c.add(heading("All bosses", null));
		Set<String> hidden = new HashSet<>(ledger.prefs().hiddenBosses);
		Map<String, List<Boss>> byCat = new LinkedHashMap<>();
		for (Boss b : Catalog.ordered(ledger.data()))
		{
			if (!hidden.contains(b.id))
			{
				byCat.computeIfAbsent(b.cat, k -> new ArrayList<>()).add(b);
			}
		}
		if (byCat.isEmpty())
		{
			c.add(Look.wrapped("Every boss is unticked under Settings, Bosses to show.", Look.PAGE_TEXT, Look.small(), Look.MUTED));
		}
		for (Map.Entry<String, List<Boss>> e : byCat.entrySet())
		{
			Column list = new Column(2);
			for (Boss b : e.getValue())
			{
				list.add(bossButton(b));
			}
			String cat = e.getKey();
			c.add(new Fold(cat + " (" + e.getValue().size() + ")", Boolean.TRUE.equals(openGroups.get(cat)), open -> openGroups.put(cat, open), list));
		}
		return c;
	}

	private JComponent bossButton(Boss b)
	{
		int kc = ledger.kc(b.id);
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(Look.CARD);
		row.setBorder(Look.pad(3, 6, 3, 6));
		row.add(Look.label(b.name, Look.small(), Look.TEXT), BorderLayout.CENTER);
		if (kc > 0)
		{
			row.add(Look.label(Fmt.num(kc), Look.small(), Look.MUTED), BorderLayout.EAST);
		}
		Look.clickable(row, () -> ctx.openBoss(b.id));
		return row;
	}

	private List<Component> found(String q)
	{
		List<Component> out = new ArrayList<>();
		List<Component> hits = new ArrayList<>();
		Set<String> hidden = new HashSet<>(ledger.prefs().hiddenBosses);
		int hiddenHits = 0;
		for (Boss b : Catalog.ordered(ledger.data()))
		{
			boolean match = b.name.toLowerCase().contains(q);
			for (String k : b.kc)
			{
				match |= k.toLowerCase().contains(q);
			}
			String viaItem = null;
			if (!match)
			{
				for (Map.Entry<String, Engine.ItemMeta> e : b.meta().entrySet())
				{
					if (!e.getValue().hidden && e.getKey().toLowerCase().contains(q))
					{
						viaItem = e.getKey();
						break;
					}
				}
			}
			if ((match || viaItem != null) && hidden.contains(b.id))
			{
				hiddenHits++;
			}
			else if (match || viaItem != null)
			{
				JComponent row = bossButton(b);
				if (viaItem != null)
				{
					Column c = new Column(0);
					c.add(row);
					c.add(Look.wrapped("drops " + Look.esc(viaItem), Look.PAGE_TEXT, Look.small(), Look.MUTED));
					hits.add(c);
				}
				else
				{
					hits.add(row);
				}
			}
		}
		if (hits.isEmpty())
		{
			out.add(Look.wrapped("No boss or item matches \"" + Look.esc(search.getText().trim()) + "\"" + (hiddenHits == 0 ? "." : " among the bosses you show."),
				Look.PAGE_TEXT, Look.small(), Look.MUTED));
		}
		else
		{
			Column c = new Column(3);
			for (Component h : hits)
			{
				c.add(h);
			}
			out.add(c);
		}
		if (hiddenHits > 0)
		{
			out.add(Look.wrapped(hiddenHits + " boss" + (hiddenHits == 1 ? "" : "es") + " you have unticked under Settings, Bosses to show, also match"
				+ (hiddenHits == 1 ? "es" : "") + ".", Look.PAGE_TEXT, Look.small(), Look.MUTED));
		}
		return out;
	}

	private JComponent help()
	{
		Column body = new Column(6);
		int w = Look.PAGE_TEXT - 4;
		body.add(Look.wrapped("<b>The percentage and the bar</b> are the chance, from all the kills you have made and the drop rate, that a player would have "
			+ "the next copy by now. With none of an item that is the chance of getting one: 85% means you are overdue, and the bar fills as you go dry. "
			+ "When a drop lands the bar falls to the chance of one more, not to zero, because the kills you made beyond the rate count towards the next copy. "
			+ "<b>The word and colour</b> (Dry, On rate, Lucky…) say how your count of copies compares with the kills you have made. "
			+ "Items shared by several bosses (like Virtus) use all those bosses together, so they read the same everywhere even though each boss has its own rate.",
			w, Look.small(), Look.TEXT));
		body.add(Look.wrapped("<b>Click an item</b> to see the figures behind it: the chance of at least one in all your kills, the dry streak since your last copy, "
			+ "the next " + ledger.prefs().window + " kills, and a chart. <b>Dry streak</b> is how long you have gone since your last copy; it says nothing about your next kill, "
			+ "which has the same chance whenever your last drop came (apart from drops with pity or ramping rates).", w, Look.small(), Look.TEXT));
		body.add(Look.wrapped("<b>Biggest Spoons</b> lists the items you have been luckiest with, luckiest first. <b>Bosses to show</b> in Settings picks the "
			+ "bosses this page lists; the kills and drops of the others are still recorded.", w, Look.small(), Look.TEXT));
		body.add(Look.wrapped("<b>Settings that change a rate</b> (task, Combat Achievements, team size, your share of the damage…) live under Boss settings. "
			+ "Use \"new kills only\" if a setting changed part-way through your kills.", w, Look.small(), Look.TEXT));
		body.add(Look.wrapped("<b>Importing.</b> No source records which kill each past drop came on. Your game history brings in kill counts and the items you own; "
			+ "those count towards your luck, but they have no kill number, so no dry streak is shown until you add one. Items that drop in stacks (huasca seeds, venom sacs) "
			+ "are turned into an estimated number of drops.", w, Look.small(), Look.TEXT));
		body.add(Look.wrapped("<b>Accuracy.</b> Rates come from the OSRS Wiki drop tables, checked twice. Each boss page lists anything that could not be captured.",
			w, Look.small(), Look.TEXT));
		return new Fold("How to read the numbers", openHelp, open -> openHelp = open, body);
	}
}
