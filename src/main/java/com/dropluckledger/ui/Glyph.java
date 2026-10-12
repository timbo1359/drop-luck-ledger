package com.dropluckledger.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import javax.swing.Icon;

/**
 * Small drawn symbols, so that nothing depends on which characters a font has.
 */
final class Glyph implements Icon
{
	enum Kind
	{
		RIGHT, DOWN, LEFT, CROSS
	}

	private final Kind kind;
	private final Color color;
	private final int size;

	private Glyph(Kind kind, Color color, int size)
	{
		this.kind = kind;
		this.color = color;
		this.size = size;
	}

	static Glyph of(Kind kind, Color color)
	{
		return new Glyph(kind, color, 10);
	}

	static Glyph of(Kind kind)
	{
		return new Glyph(kind, Look.MUTED, 10);
	}

	Kind kind()
	{
		return kind;
	}

	@Override
	public void paintIcon(Component c, Graphics g0, int x, int y)
	{
		Graphics2D g = (Graphics2D) g0.create();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.translate(x, y);
			g.setColor(color);
			double s = size;
			Path2D.Double p = new Path2D.Double();
			switch (kind)
			{
				case RIGHT:
					p.moveTo(s * 0.3, s * 0.15);
					p.lineTo(s * 0.8, s * 0.5);
					p.lineTo(s * 0.3, s * 0.85);
					p.closePath();
					g.fill(p);
					break;
				case DOWN:
					p.moveTo(s * 0.15, s * 0.3);
					p.lineTo(s * 0.85, s * 0.3);
					p.lineTo(s * 0.5, s * 0.8);
					p.closePath();
					g.fill(p);
					break;
				case LEFT:
					g.setStroke(new java.awt.BasicStroke(1.8f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
					p.moveTo(s * 0.65, s * 0.15);
					p.lineTo(s * 0.3, s * 0.5);
					p.lineTo(s * 0.65, s * 0.85);
					g.draw(p);
					break;
				case CROSS:
				default:
					g.setStroke(new java.awt.BasicStroke(1.6f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
					g.drawLine((int) (s * 0.25), (int) (s * 0.25), (int) (s * 0.75), (int) (s * 0.75));
					g.drawLine((int) (s * 0.75), (int) (s * 0.25), (int) (s * 0.25), (int) (s * 0.75));
					break;
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
		return size;
	}

	@Override
	public int getIconHeight()
	{
		return size;
	}
}
