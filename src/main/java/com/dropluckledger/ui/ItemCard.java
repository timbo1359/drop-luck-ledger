package com.dropluckledger.ui;

import com.dropluckledger.engine.Luck;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * One tracked item, on a single compact row.  The percentage and the bar are the chance that a player would have had the next copy by
 * now, counting every kill made: 85% for someone who is dry, and after a drop it falls to the chance of one more, not to zero, because
 * the kills beyond the drop rate carry over.  The word (Dry, On rate, Lucky) says how the player's copies compare with what is likely.
 * Everything else is in the tooltip, and a click opens the figures, the chart and the buttons.
 */
@SuppressWarnings("serial")
final class ItemCard extends Column
{
	/**
	 * What the card's buttons and clicks do.
	 */
	interface Actions
	{
		void log(String item);

		void hide(String item);

		void toggle(String item);

		boolean isOpen(String item);

		void chart(String item, Consumer<Views.ChartData> into);
	}

	/**
	 * The luck figures the card leads with: the pooled ones when several bosses drop the item, so that the same item reads the
	 * same on each of those bosses' pages.
	 */
	private static final class Figures
	{
		final Double pct;
		final Luck.Label label;
		final int got;
		final Double expected;
		final boolean pooled;
		private final double poolChance;
		private final Double anotherChance;

		Figures(Views.ItemView v)
		{
			poolChance = v.pool != null ? v.pool.another : 0;
			anotherChance = v.pool != null ? null : v.a.another;
			if (v.pool != null)
			{
				pct = v.pool.pct;
				label = v.pool.label;
				got = v.pool.O;
				expected = v.pool.expected;
				pooled = true;
			}
			else
			{
				pct = v.a.pct;
				label = v.a.label;
				got = v.a.O;
				expected = v.a.expected;
				pooled = false;
			}
		}

		boolean known()
		{
			return pct != null && !Double.isNaN(pct) && label != null && !"na".equals(label.key);
		}

		/**
		 * The chance that the next copy would have come by now (the first one, while there is none).
		 */
		double chance()
		{
			Double c = pooled ? Double.valueOf(poolChance) : anotherChance;
			return c == null ? Double.NaN : c;
		}
	}

	private final Views.ItemView view;
	private final Actions actions;
	private final CurveChart chart = new CurveChart();
	private final Meter meter = new Meter(5);
	private final JButton logButton;
	private final JButton hideButton;

	ItemCard(Ctx ctx, Views.ItemView v, Actions actions)
	{
		super(3, true);
		this.view = v;
		this.actions = actions;
		setBackground(Look.CARD);
		setBorder(Look.card());
		final Luck.ItemAnalysis a = v.a;
		final boolean open = actions.isOpen(v.name);
		logButton = Look.button("Log drop");
		logButton.addActionListener(e -> actions.log(v.name));
		hideButton = Look.button("Hide");
		hideButton.setToolTipText("Stop tracking this item");
		hideButton.addActionListener(e -> actions.hide(v.name));

		if (a == null)
		{
			add(top(ctx, v, null, null, Look.CARD_TEXT));
			add(Look.wrapped("Could not work this out.", Look.CARD_TEXT, Look.small(), Look.MUTED));
			if (open)
			{
				add(footer());
			}
			Look.clickable(this, () -> actions.toggle(v.name));
			return;
		}

		final Figures f = new Figures(v);
		final boolean done = a.collected;
		final double chance = f.chance();
		final boolean has = a.K > 0 && !Double.isNaN(chance);
		final Color color = done ? Look.GOOD : has ? Look.chance(chance) : Look.MUTED;
		final String headline = done ? "Done" : has ? Fmt.pct(chance) : "—";
		add(top(ctx, v, headline, color, Look.CARD_TEXT));
		meter.set(done ? 1 : has ? chance : 0, color);
		add(meter);
		add(sub(v, f, done, color));

		if (open)
		{
			if (a.K > 0)
			{
				add(Look.kv(Look.CARD_TEXT, facts(v, f, done)));
			}
			if (v.pool != null)
			{
				add(pooled(v.pool));
			}
			String extras = extrasOf(v);
			if (!extras.isEmpty())
			{
				add(Look.wrapped(Look.esc(extras), Look.CARD_TEXT, Look.small(), Look.MUTED));
			}
			if (v.meta != null && v.meta.note != null && !v.meta.note.isEmpty())
			{
				add(Look.wrapped(Look.esc(v.meta.note), Look.CARD_TEXT, Look.small(), Look.MUTED));
			}
			if (a.K > 0 && !done && v.pool == null && a.streakKnown)
			{
				add(Look.wrapped("Chance of getting it within a number of kills after your last one", Look.CARD_TEXT, Look.small(), Look.MUTED));
				add(chart);
				actions.chart(v.name, chart::set);
			}
			else if (a.K > 0 && !done && !streakKnown(v))
			{
				add(Look.wrapped("Your last copy has no kill number, so the dry streak and its chart are left out. Edit the drop in the history to add one.",
					Look.CARD_TEXT, Look.small(), Look.MUTED));
			}
			add(footer());
		}
		setToolTipText(tip(v, f, done));
		Look.clickable(this, () -> actions.toggle(v.name));
	}

	// ------------------------------------------------------------------ the row

	/**
	 * The icon, the name and, on the right, the headline.
	 */
	private JPanel top(Ctx ctx, Views.ItemView v, String headline, Color color, int width)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		java.awt.Font big = Look.bold().deriveFont(Look.bold().getSize2D() + 2f);
		final int headWidth = headline == null ? 0 : getFontMetrics(big).stringWidth(headline) + 4;
		int tw = width - headWidth - (ctx.pictures() ? 36 + 6 : 0) - 6;
		if (ctx.pictures())
		{
			JLabel icon = new JLabel();
			ItemIcon.set(icon, ctx.icons(), v.name, true, 30, 28);
			icon.setVerticalAlignment(SwingConstants.CENTER);
			row.add(icon, BorderLayout.WEST);
		}
		Luck.ItemAnalysis a = v.a;
		String title = Look.esc(v.name) + (a != null && a.O > 0 ? " " + Look.colored("×" + a.O, Look.GOOD) : "");
		JLabel name = Look.wrapped("<b>" + title + "</b>", tw, Look.body(), Look.TEXT);
		name.setVerticalAlignment(SwingConstants.CENTER);
		row.add(name, BorderLayout.CENTER);
		if (headline != null)
		{
			JLabel pc = Look.label(headline, big, color);
			pc.setHorizontalAlignment(SwingConstants.RIGHT);
			row.add(pc, BorderLayout.EAST);
		}
		return row;
	}

	/**
	 * The muted line under the bar: the rate and what has been received against what to expect, with the word for the luck.
	 */
	private static JPanel sub(Views.ItemView v, Figures f, boolean done, Color color)
	{
		Luck.ItemAnalysis a = v.a;
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setOpaque(false);
		String left;
		if (done)
		{
			left = "Collected · no longer rolls";
		}
		else if (a.K == 0)
		{
			left = "Add your kill count";
		}
		else
		{
			// a chance of 0 is about the current settings (another reward, another mode) or the duplicate protection, not the item
			String rate = a.pNext > 0 ? Fmt.rate(a.pNext) : a.once && a.O > 0 ? "once only" : protectedNow(v) ? "after the set" : "not now";
			if (f.pooled)
			{
				left = "pooled · " + f.got + " vs " + (f.expected == null ? "—" : Fmt.expected(f.expected)) + " expected";
			}
			else
			{
				left = rate + " · " + f.got + " vs " + (f.expected == null ? "—" : Fmt.expected(f.expected)) + " expected";
			}
		}
		JLabel l = Look.label(left, Look.small(), Look.MUTED);
		row.add(l, BorderLayout.CENTER);
		if (!done && a.K > 0 && f.known())
		{
			row.add(Look.label(f.label.text, Look.small(), Look.luck(f.label.key)), BorderLayout.EAST);
		}
		return row;
	}

	private JPanel footer()
	{
		JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		p.setOpaque(false);
		p.add(logButton);
		p.add(hideButton);
		return p;
	}

	// ------------------------------------------------------------------ the words

	/**
	 * Whether anyone can say how long ago the last copy was.  For an item pooled over several bosses that is the pool's streak.
	 */
	private static boolean streakKnown(Views.ItemView v)
	{
		return v.pool != null ? v.pool.streakKnown : v.a.streakKnown;
	}

	private static String tagsOf(Views.ItemView v)
	{
		String extras = extrasOf(v);
		return extras.isEmpty() ? v.kind : v.kind + " · " + extras;
	}

	/**
	 * What is special about the item, beyond being a unique, a pet or a drop.
	 */
	private static String extrasOf(Views.ItemView v)
	{
		List<String> t = new ArrayList<>();
		if (v.meta != null && v.meta.seq != null)
		{
			t.add("hit " + v.meta.seq.n + " of " + v.meta.seq.len);
		}
		if (v.meta != null && v.meta.pity > 0)
		{
			t.add("pity " + v.meta.pity);
		}
		if (v.shared)
		{
			t.add("shared by several bosses");
		}
		return String.join(" · ", t);
	}

	private static String rateOf(Views.ItemView v)
	{
		Luck.ItemAnalysis a = v.a;
		String rate = a.pNext > 0 ? Fmt.rate(a.pNext) : a.once && a.O > 0 ? "once only"
			: protectedNow(v) ? "0 for now: duplicate protection gives the pieces of the set you have fewer of first"
			: "0 with the current settings (another reward or mode)";
		return a.seq != null ? rate + " · slot hits so far " + a.hits : rate;
	}

	/**
	 * True when the item cannot drop now because of duplicate protection: other pieces of its set come first.
	 */
	private static boolean protectedNow(Views.ItemView v)
	{
		return v.meta != null && v.meta.nodup != null && v.a.pNext == 0 && v.a.O > 0;
	}

	/**
	 * Everything about the item, for hovering.
	 */
	private static String tip(Views.ItemView v, Figures f, boolean done)
	{
		Luck.ItemAnalysis a = v.a;
		StringBuilder b = new StringBuilder("<b>").append(Look.esc(v.name)).append("</b> ").append(Look.dim(Look.esc(tagsOf(v))));
		if (done)
		{
			b.append("<br>Collected: this drop no longer rolls.");
			return Look.tip(b.toString());
		}
		if (a.K == 0)
		{
			b.append("<br>Set your kill count to see how lucky you are.");
			return Look.tip(b.toString());
		}
		b.append("<br><b>").append(Fmt.pct(f.chance())).append("</b> chance of ").append(f.got > 0 ? "copy " + (f.got + 1) : "one")
			.append(" by now, from all your kills at the drop rate. ").append(Look.dim("Kills past the rate carry over to the next one."));
		b.append("<br>Per kill here: ").append(Look.esc(rateOf(v)));
		if (v.pool != null)
		{
			b.append(" ").append(Look.dim("(each boss has its own rate; the luck below joins them all)"));
			b.append("<br>Across ").append(v.pool.per.size()).append(" bosses (").append(Look.esc(perBoss(v.pool))).append("): got ").append(f.got).append(" vs ")
				.append(f.expected == null ? "—" : Fmt.expected(f.expected)).append(" expected");
		}
		else
		{
			b.append("<br>Received ").append(f.got).append(" vs ").append(f.expected == null ? "—" : Fmt.expected(f.expected)).append(" expected over ")
				.append(Fmt.num(a.K)).append(" kills");
		}
		if (f.known())
		{
			b.append("<br>Luck: ").append(Look.colored("<b>" + Look.esc(f.label.text) + "</b>", Look.luck(f.label.key))).append(" · luckier than ")
				.append(Fmt.pct(f.pct)).append(" of players with the same kills");
		}
		Double cum = v.pool != null ? (Double) v.pool.cum : a.cum;
		if (cum != null)
		{
			b.append("<br>Chance of 1+ in all those kills: ").append(Fmt.pct(cum));
		}
		b.append("<br>").append(streakLine(v));
		b.append("<br>").append(Look.dim("Click for more"));
		return Look.tip(b.toString());
	}

	private static String streakLine(Views.ItemView v)
	{
		Luck.ItemAnalysis a = v.a;
		if (v.pool != null)
		{
			Luck.Pool p = v.pool;
			return p.streakKnown
				? "Dry streak over all these bosses: " + Fmt.num(p.streak) + " kills since your last (" + Fmt.pct(p.meter) + " would have had another)"
				: "Dry streak: not known, because your last copy has no kill number.";
		}
		if (!a.streakKnown)
		{
			return "Dry streak: not known, because your last copy has no kill number.";
		}
		if (a.O > 0 && a.streakFrom > 0)
		{
			return "Dry streak: " + Fmt.num(a.streak) + " kills since your last, at kill " + Fmt.num(a.streakFrom) + " (" + Fmt.pct(a.meter) + " would have had another)";
		}
		return "Dry streak: " + Fmt.num(a.streak) + " kills without one (" + Fmt.pct(a.meter) + " would have had one)";
	}

	private static String perBoss(Luck.Pool p)
	{
		List<String> parts = new ArrayList<>();
		for (Luck.PoolPer x : p.per)
		{
			parts.add(x.name + " " + Fmt.num(x.K));
		}
		return String.join(" · ", parts);
	}

	private static List<String[]> facts(Views.ItemView v, Figures f, boolean done)
	{
		Luck.ItemAnalysis a = v.a;
		List<String[]> rows = new ArrayList<>();
		rows.add(new String[] {"Per kill here", Look.esc(rateOf(v))});
		rows.add(new String[] {"Received vs expected", a.O + " vs " + (a.expected == null ? "—" : Fmt.expected(a.expected))});
		if (!done && !Double.isNaN(f.chance()))
		{
			rows.add(new String[] {f.got > 0 ? "Chance of copy " + (f.got + 1) : "Chance of one", Look.esc(Fmt.pct(f.chance()))});
		}
		if (f.known())
		{
			rows.add(new String[] {"Luck", Look.colored(Look.esc(f.label.text), Look.luck(f.label.key))});
			rows.add(new String[] {"Luckier than", Look.esc(Fmt.pct(f.pct)) + " of players"});
		}
		if (!done && a.cum != null)
		{
			rows.add(new String[] {"1+ in all kills", Look.esc(Fmt.pct(a.cum))});
		}
		if (!done)
		{
			boolean pooled = v.pool != null;
			if (streakKnown(v))
			{
				long streak = pooled ? v.pool.streak : a.streak;
				double odds = pooled ? v.pool.meter : a.meter;
				boolean again = pooled ? v.pool.O > 0 : a.O > 0;
				rows.add(new String[] {pooled ? "Dry streak, all bosses" : "Dry streak", Fmt.num(streak) + " kills"});
				rows.add(new String[] {again ? "Odds of another by now" : "Odds of one by now", Look.esc(Fmt.pct(odds))});
			}
			Double fwd = a.fwd.get(a.window);
			rows.add(new String[] {"Next " + a.window + " kills", Look.esc(Fmt.pct(fwd))});
			if (a.kto != null)
			{
				Double half = a.kto.get(0.5);
				Double most = a.kto.get(0.9);
				rows.add(new String[] {"50% / 90% in", Fmt.num(half == null ? Double.POSITIVE_INFINITY : half) + " / "
					+ Fmt.num(most == null ? Double.POSITIVE_INFINITY : most) + " kills"});
			}
		}
		return rows;
	}

	private static JLabel pooled(Luck.Pool p)
	{
		String m = "<b>Pooled across " + p.per.size() + " bosses</b><br>" + Look.esc(perBoss(p)) + "<br>" + p.O + " received vs " + Fmt.expected(p.expected)
			+ " expected · " + Look.colored(Look.esc(p.label.text), Look.luck(p.label.key)) + ", luckier than " + Fmt.pct(p.pct) + " of players";
		return Look.wrapped(m, Look.CARD_TEXT, Look.small(), Look.TEXT);
	}

	// ------------------------------------------------------------------ for tests

	Views.ItemView view()
	{
		return view;
	}

	CurveChart chart()
	{
		return chart;
	}

	/**
	 * Opens or closes the figures, as a click on the card does.
	 */
	void toggle()
	{
		actions.toggle(view.name);
	}

	JButton logButton()
	{
		return logButton;
	}

	JButton hideButton()
	{
		return hideButton;
	}
}
