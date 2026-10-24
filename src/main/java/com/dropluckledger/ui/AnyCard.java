package com.dropluckledger.ui;

import com.dropluckledger.engine.Luck;
import java.awt.BorderLayout;
import java.awt.Color;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * "Any unique": how lucky the player has been with the boss's uniques as a whole (or with its unique table, where the boss rolls
 * the rest separately).  One compact row like an item card; the details are in the tooltip.
 */
@SuppressWarnings("serial")
final class AnyCard extends Column
{
	private final Meter meter = new Meter(5);

	AnyCard(Views.AnyView any)
	{
		super(3, true);
		setBackground(Look.CARD);
		setBorder(javax.swing.BorderFactory.createCompoundBorder(javax.swing.BorderFactory.createLineBorder(Look.GOLD, 1), Look.pad(4, 6, 4, 6)));
		final Luck.AnyUnique u = any.u;
		final boolean table = any.isTable();
		final boolean known = !Double.isNaN(u.pct) && u.label != null && !"na".equals(u.label.key);
		final Color color = known ? Look.luck(u.label.key) : Look.MUTED;
		final Color barColor = Look.chance(u.another);

		JPanel top = new JPanel(new BorderLayout(6, 0));
		top.setOpaque(false);
		JLabel name = Look.wrapped("<b>" + (table ? "Unique table" : "Any unique") + "</b> " + Look.dim(any.names.size() + " items"), Look.CARD_TEXT - 60,
			Look.body(), Look.TEXT);
		top.add(name, BorderLayout.CENTER);
		JLabel pc = Look.label(Fmt.pct(u.another), Look.bold().deriveFont(Look.bold().getSize2D() + 2f), barColor);
		pc.setHorizontalAlignment(SwingConstants.RIGHT);
		top.add(pc, BorderLayout.EAST);
		add(top);

		meter.set(u.another, barColor);
		add(meter);

		JPanel sub = new JPanel(new BorderLayout(4, 0));
		sub.setOpaque(false);
		sub.add(Look.label(Fmt.rate(u.pNext) + " · " + u.O + " vs " + Fmt.expected(u.expected) + " expected", Look.small(), Look.MUTED), BorderLayout.CENTER);
		if (known)
		{
			sub.add(Look.label(u.label.text, Look.small(), color), BorderLayout.EAST);
		}
		add(sub);
		setToolTipText(tip(any, table, known));
	}

	private static String tip(Views.AnyView any, boolean table, boolean known)
	{
		Luck.AnyUnique u = any.u;
		StringBuilder b = new StringBuilder("<b>").append(table ? "Unique table" : "Any unique").append("</b><br>").append(Look.esc(describe(any, table)));
		b.append("<br><b>").append(Fmt.pctHtml(u.another)).append("</b> chance of ").append(u.O > 0 ? "another " + (table ? "table hit" : "unique") : "one")
			.append(" by now, from all your kills. ").append(Look.dim("Kills past the rate carry over to the next one."));
		b.append("<br>Per kill: ").append(Look.esc(Fmt.rate(u.pNext)));
		b.append("<br>").append(table ? "Table hits" : "Uniques").append(": ").append(u.O).append(" vs ").append(Fmt.expected(u.expected)).append(" expected");
		if (known)
		{
			b.append("<br>Luck: ").append(Look.colored("<b>" + Look.esc(u.label.text) + "</b>", Look.luck(u.label.key))).append(" · luckier than ")
				.append(Fmt.pctHtml(u.pct)).append(" of players with the same kills");
		}
		b.append("<br>Chance of 1+ in all your kills: ").append(Fmt.pctHtml(u.cum));
		b.append("<br>Next ").append(u.window).append(" kills: ").append(Fmt.pctHtml(u.fwd.get(u.window)));
		if (u.streakKnown)
		{
			b.append("<br>Dry streak: ").append(Fmt.num(u.streak)).append(" kills since your last (").append(Fmt.pctHtml(u.meter)).append(" would have had another)");
		}
		else
		{
			b.append("<br>Dry streak: not known, because your last ").append(table ? "table hit" : "unique").append(" has no kill number.");
		}
		return Look.tip(b.toString());
	}

	private static String describe(Views.AnyView any, boolean table)
	{
		List<String> shown = any.names.subList(0, Math.min(6, any.names.size()));
		String list = String.join(", ", shown) + (any.names.size() > 6 ? "…" : "");
		if (table)
		{
			return "Chance of hitting the unique table: " + list + ". "
				+ (any.gold ? "The vestige slot counts on every one of its rolls (the first two drop gold rings, the third the vestige). " : "")
				+ String.join(", ", any.separate) + (any.separate.size() > 1 ? " are" : " is") + " rolled separately and has its own card.";
		}
		return "At least one of: " + list + ".";
	}
}
