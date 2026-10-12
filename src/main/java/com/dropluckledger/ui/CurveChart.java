package com.dropluckledger.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import javax.swing.JComponent;

/**
 * The curve of the chance of getting a drop within a number of kills after the last one, with a line where the player is now.
 */
@SuppressWarnings("serial")
final class CurveChart extends JComponent
{
	private static final int LEFT = 30;
	private static final int RIGHT = 6;
	private static final int TOP = 8;
	private static final int BOTTOM = 16;

	private Views.ChartData data;

	CurveChart()
	{
		setOpaque(false);
		setFont(Look.small());
		setPreferredSize(new Dimension(Look.CARD_TEXT, 110));
	}

	void set(Views.ChartData d)
	{
		this.data = d;
		repaint();
	}

	boolean hasData()
	{
		return data != null;
	}

	@Override
	protected void paintComponent(Graphics g0)
	{
		Views.ChartData d = data;
		if (d == null)
		{
			return;
		}
		Graphics2D g = (Graphics2D) g0.create();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setFont(getFont());
			FontMetrics fm = g.getFontMetrics();
			int w = getWidth();
			int h = getHeight();
			int pw = w - LEFT - RIGHT;
			int ph = h - TOP - BOTTOM;
			// horizontal guides
			double[] marks = {0, 0.5, 0.9, 1};
			for (double y : marks)
			{
				int py = TOP + (int) Math.round(ph * (1 - y));
				g.setColor(y == 0 || y == 1 ? Look.LINE : new Color(255, 255, 255, 40));
				if (y != 0 && y != 1)
				{
					g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {2f, 3f}, 0f));
				}
				else
				{
					g.setStroke(new BasicStroke(1f));
				}
				g.drawLine(LEFT, py, w - RIGHT, py);
				if (y < 1)
				{
					g.setColor(Look.MUTED);
					String t = Math.round(y * 100) + "%";
					g.drawString(t, LEFT - 3 - fm.stringWidth(t), py + fm.getAscent() / 2 - 1);
				}
			}
			g.setStroke(new BasicStroke(1f));
			// distances along the bottom
			for (int i = 0; i <= 2; i++)
			{
				double x = d.xmax * i / 2;
				String t = Fmt.num(x);
				int px = LEFT + (int) Math.round(pw * i / 2.0);
				int tx = i == 0 ? px : i == 2 ? px - fm.stringWidth(t) : px - fm.stringWidth(t) / 2;
				g.setColor(Look.MUTED);
				g.drawString(t, tx, h - 3);
			}
			// the curve
			if (d.x.length > 1 && d.xmax > 0)
			{
				Path2D.Double line = new Path2D.Double();
				Path2D.Double area = new Path2D.Double();
				for (int i = 0; i < d.x.length; i++)
				{
					double px = LEFT + pw * d.x[i] / d.xmax;
					double py = TOP + ph * (1 - d.y[i]);
					if (i == 0)
					{
						line.moveTo(px, py);
						area.moveTo(px, TOP + ph);
						area.lineTo(px, py);
					}
					else
					{
						line.lineTo(px, py);
						area.lineTo(px, py);
					}
				}
				area.lineTo(LEFT + pw, TOP + ph);
				area.closePath();
				g.setColor(new Color(Look.BLUE.getRed(), Look.BLUE.getGreen(), Look.BLUE.getBlue(), 45));
				g.fill(area);
				g.setColor(Look.BLUE);
				g.setStroke(new BasicStroke(1.6f));
				g.draw(line);
			}
			// where the player is
			if (d.xmax > 0)
			{
				int px = LEFT + (int) Math.round(pw * Math.min(1.0, d.you / d.xmax));
				g.setColor(Look.EMBER);
				g.setStroke(new BasicStroke(1.5f));
				g.drawLine(px, TOP, px, TOP + ph);
				String t = "you: " + Fmt.num(d.you);
				int tx = Math.min(px + 4, w - RIGHT - fm.stringWidth(t));
				g.drawString(t, Math.max(LEFT, tx), TOP + fm.getAscent());
			}
		}
		finally
		{
			g.dispose();
		}
	}
}
