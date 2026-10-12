package com.dropluckledger.ui;

import com.dropluckledger.plugin.ItemIcons;
import java.awt.Color;
import java.awt.Component;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.Icon;
import javax.swing.JLabel;

/**
 * An item's picture from the game, drawn at a fixed size.  The game fills the picture in a moment after asking for it, so
 * the label that shows it is repainted when it is ready.  Without a picture a lettered square is drawn instead.
 */
final class ItemIcon implements Icon
{
	private final BufferedImage image;
	private final String letter;
	private final int width;
	private final int height;

	private ItemIcon(BufferedImage image, String name, int width, int height)
	{
		this.image = image;
		this.letter = name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
		this.width = width;
		this.height = height;
	}

	/**
	 * Sets the icon of a label.  Pictures are left out when the player turned them off.
	 */
	static void set(JLabel label, ItemIcons icons, String name, boolean show, int width, int height)
	{
		label.setIconTextGap(6);
		if (!show || icons == null)
		{
			label.setIcon(null);
			return;
		}
		label.setIcon(new ItemIcon(icons.image(name, label::repaint), name, width, height));
	}

	@Override
	public void paintIcon(Component c, Graphics g0, int x, int y)
	{
		Graphics2D g = (Graphics2D) g0.create();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			if (image != null)
			{
				// keep the picture's proportions inside the box
				int iw = image.getWidth();
				int ih = image.getHeight();
				double s = Math.min(width / (double) iw, height / (double) ih);
				int w = Math.max(1, (int) Math.round(iw * s));
				int h = Math.max(1, (int) Math.round(ih * s));
				g.drawImage(image, x + (width - w) / 2, y + (height - h) / 2, w, h, null);
			}
			else
			{
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setColor(new Color(60, 60, 60));
				g.fillRoundRect(x + 2, y + 2, width - 4, height - 4, 6, 6);
				g.setColor(Look.MUTED);
				g.setFont(Look.small());
				FontMetrics fm = g.getFontMetrics();
				g.drawString(letter, x + (width - fm.stringWidth(letter)) / 2, y + (height - fm.getHeight()) / 2 + fm.getAscent());
			}
		}
		finally
		{
			g.dispose();
		}
	}

	@Override
	public int getIconWidth()
	{
		return width;
	}

	@Override
	public int getIconHeight()
	{
		return height;
	}
}
