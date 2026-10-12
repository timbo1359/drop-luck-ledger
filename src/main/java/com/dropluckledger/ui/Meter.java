package com.dropluckledger.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JComponent;

/**
 * A thin bar showing a chance from 0 to 100%, with optional marks (the card's luck bar has one at 50%, "exactly on rate").
 */
@SuppressWarnings("serial")
final class Meter extends JComponent
{
	private double value;
	private Color color = Look.BLUE;
	private final double[] marks;

	/**
	 * @param marks where to draw thin lines across the bar, as fractions of its length
	 */
	Meter(int height, double... marks)
	{
		this.marks = marks;
		setOpaque(false);
		setPreferredSize(new Dimension(10, height));
		setMinimumSize(new Dimension(10, height));
	}

	void set(double v, Color c)
	{
		this.value = Double.isNaN(v) ? 0 : Math.max(0, Math.min(1, v));
		this.color = c;
		repaint();
	}

	double value()
	{
		return value;
	}

	@Override
	protected void paintComponent(Graphics g0)
	{
		Graphics2D g = (Graphics2D) g0.create();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth();
			int h = getHeight();
			g.setColor(Look.LINE);
			g.fillRoundRect(0, 0, w, h, h, h);
			if (value > 0)
			{
				int fill = Math.max(h, (int) Math.round(w * value));
				g.setColor(color);
				g.fillRoundRect(0, 0, fill, h, h, h);
			}
			g.setColor(new Color(0, 0, 0, 140));
			for (double m : marks)
			{
				g.fillRect((int) Math.round(w * m), 0, 1, h);
			}
		}
		finally
		{
			g.dispose();
		}
	}
}
