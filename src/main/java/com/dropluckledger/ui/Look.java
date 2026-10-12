package com.dropluckledger.ui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.border.Border;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Colours, fonts and small builders shared by the panel's parts.
 */
final class Look
{
	/**
	 * Width for wrapped text straight on the page.  The side panel is 242 pixels wide (225 and the 17 RuneLite keeps for a scrollbar);
	 * the page has 6 pixels of padding on each side and, when it is long, a scrollbar of 7, which leaves 223 for the content.
	 */
	static final int PAGE_TEXT = 218;
	/**
	 * Width for wrapped text inside a card (the card's border and padding, 16 pixels, taken off the content width).
	 */
	static final int CARD_TEXT = 202;

	/**
	 * Width for the wrapped text of a tooltip.
	 */
	static final int TIP = 236;

	static final Color BG = ColorScheme.DARK_GRAY_COLOR;
	static final Color CARD = ColorScheme.DARKER_GRAY_COLOR;
	static final Color CARD_HOVER = ColorScheme.DARKER_GRAY_HOVER_COLOR;
	static final Color LINE = ColorScheme.MEDIUM_GRAY_COLOR;
	static final Color TEXT = new Color(0xe6, 0xe6, 0xe6);
	static final Color MUTED = ColorScheme.LIGHT_GRAY_COLOR;
	static final Color ACCENT = ColorScheme.BRAND_ORANGE;

	static final Color BLUE = new Color(0x6a, 0xa5, 0xd6);
	static final Color GOLD = new Color(0xe0, 0xae, 0x2c);
	static final Color EMBER = new Color(0xe8, 0x79, 0x4f);
	static final Color GOOD = new Color(0x5e, 0xc1, 0x84);
	static final Color WARN = new Color(0xe3, 0xa2, 0x4d);
	static final Color BAD = new Color(0xee, 0x84, 0x66);

	private Look()
	{
	}

	static Font small()
	{
		return FontManager.getRunescapeSmallFont();
	}

	static Font body()
	{
		return FontManager.getRunescapeFont();
	}

	static Font bold()
	{
		return FontManager.getRunescapeBoldFont();
	}

	/**
	 * The colour of a "chance by now" bar: blue while it is still likely you would not have had it, gold past even odds, ember past
	 * nine in ten (well overdue).
	 */
	static Color chance(double c)
	{
		return c < 0.5 ? BLUE : c < 0.9 ? GOLD : EMBER;
	}

	/**
	 * The colour of a luck label ("vdry", "dry", "below", "avg", "above", "lucky", "vlucky", "na").
	 */
	static Color luck(String key)
	{
		switch (key)
		{
			case "vdry":
			case "dry":
				return BAD;
			case "below":
				return WARN;
			case "above":
			case "lucky":
			case "vlucky":
				return GOOD;
			case "avg":
				return TEXT;
			default:
				return MUTED;
		}
	}

	static String esc(String s)
	{
		if (s == null)
		{
			return "";
		}
		StringBuilder b = new StringBuilder(s.length() + 8);
		for (int i = 0; i < s.length(); i++)
		{
			char c = s.charAt(i);
			switch (c)
			{
				case '&':
					b.append("&amp;");
					break;
				case '<':
					b.append("&lt;");
					break;
				case '>':
					b.append("&gt;");
					break;
				case '"':
					b.append("&quot;");
					break;
				default:
					b.append(c);
			}
		}
		return b.toString();
	}

	/**
	 * "#rrggbb" for use in label markup.
	 */
	static String hex(Color c)
	{
		return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
	}

	static JLabel label(String text, Font font, Color color)
	{
		JLabel l = new JLabel(text);
		l.setFont(font);
		l.setForeground(color);
		return l;
	}

	/**
	 * Label markup that wraps at the given width in pixels.  The width is given as an attribute because Swing reads a CSS width in
	 * "px" as 1.3 pixels each.
	 */
	static String html(int width, String markup)
	{
		return "<html><body width='" + width + "'>" + markup + "</body></html>";
	}

	/**
	 * Tooltip markup: wraps at {@link #TIP} pixels.
	 */
	static String tip(String markup)
	{
		return html(TIP, markup);
	}

	/**
	 * Markup for a muted piece of text inside a label of another colour.
	 */
	static String dim(String escapedText)
	{
		return colored(escapedText, MUTED);
	}

	/**
	 * A label whose text (already escaped markup) wraps at the given width.
	 */
	static JLabel wrapped(String markup, int width, Font font, Color color)
	{
		JLabel l = new JLabel(html(width, markup));
		l.setFont(font);
		l.setForeground(color);
		l.setVerticalAlignment(SwingConstants.TOP);
		return l;
	}

	/**
	 * A list of labelled values: the label on the left, the value (markup) on the right.
	 */
	static JLabel kv(int width, java.util.List<String[]> rows)
	{
		StringBuilder b = new StringBuilder("<table cellpadding='1' cellspacing='0' width='").append(width).append("'>");
		for (String[] r : rows)
		{
			b.append("<tr><td valign='top'><font color='").append(hex(MUTED)).append("'>").append(esc(r[0]).replace(" ", "&nbsp;")).append("</font></td>")
				.append("<td align='right' valign='top'>").append(r[1]).append("</td></tr>");
		}
		b.append("</table>");
		JLabel l = new JLabel(html(width, b.toString()));
		l.setFont(small());
		l.setForeground(TEXT);
		l.setVerticalAlignment(SwingConstants.TOP);
		return l;
	}

	/**
	 * Markup that colours a piece of text.
	 */
	static String colored(String escapedText, Color c)
	{
		return "<font color='" + hex(c) + "'>" + escapedText + "</font>";
	}

	static JLabel muted(String text)
	{
		return label(text, small(), MUTED);
	}

	static Border pad(int top, int left, int bottom, int right)
	{
		return BorderFactory.createEmptyBorder(top, left, bottom, right);
	}

	static Border card()
	{
		return BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(ColorScheme.BORDER_COLOR, 1), pad(4, 6, 4, 6));
	}

	/**
	 * A text-only button that looks like a link.
	 */
	static JButton link(String text)
	{
		JButton b = new JButton(text);
		b.setFont(small());
		b.setForeground(ACCENT);
		b.setBorderPainted(false);
		b.setContentAreaFilled(false);
		b.setFocusPainted(false);
		b.setOpaque(false);
		b.setMargin(new java.awt.Insets(0, 0, 0, 0));
		b.setBorder(pad(1, 0, 1, 0));
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		b.setHorizontalAlignment(SwingConstants.LEFT);
		return b;
	}

	/**
	 * A small ordinary button.
	 */
	static JButton button(String text)
	{
		JButton b = new JButton(text);
		b.setFont(small());
		b.setFocusPainted(false);
		b.setMargin(new java.awt.Insets(2, 6, 2, 6));
		return b;
	}

	/**
	 * Makes a panel react to the mouse like a button.
	 */
	static void clickable(JComponent c, Runnable action)
	{
		c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		c.addMouseListener(new MouseAdapter()
		{
			private Color normal;

			@Override
			public void mouseClicked(MouseEvent e)
			{
				action.run();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				normal = c.getBackground();
				c.setBackground(CARD_HOVER);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				if (normal != null)
				{
					c.setBackground(normal);
				}
			}
		});
	}
}
