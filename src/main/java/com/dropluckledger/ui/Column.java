package com.dropluckledger.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;

/**
 * A panel that stacks its parts from top to bottom.  Every part is as wide as the panel and as tall as it wants to be.
 * Inside a scroll pane it follows the width of the view, so nothing scrolls sideways.
 */
@SuppressWarnings("serial")
class Column extends JPanel implements Scrollable
{
	Column(int gap)
	{
		super(new Stack(gap));
		setOpaque(false);
	}

	Column(int gap, boolean opaque)
	{
		this(gap);
		setOpaque(opaque);
		if (opaque)
		{
			setBackground(Look.BG);
		}
	}

	@Override
	public Dimension getPreferredScrollableViewportSize()
	{
		return getPreferredSize();
	}

	@Override
	public int getScrollableUnitIncrement(java.awt.Rectangle visibleRect, int orientation, int direction)
	{
		return 24;
	}

	@Override
	public int getScrollableBlockIncrement(java.awt.Rectangle visibleRect, int orientation, int direction)
	{
		return orientation == SwingConstants.VERTICAL ? Math.max(24, visibleRect.height - 24) : visibleRect.width;
	}

	@Override
	public boolean getScrollableTracksViewportWidth()
	{
		return true;
	}

	@Override
	public boolean getScrollableTracksViewportHeight()
	{
		return false;
	}

	/**
	 * Top to bottom, with a gap between the parts.
	 */
	static final class Stack implements LayoutManager
	{
		private final int gap;

		Stack(int gap)
		{
			this.gap = gap;
		}

		@Override
		public void addLayoutComponent(String name, Component comp)
		{
		}

		@Override
		public void removeLayoutComponent(Component comp)
		{
		}

		@Override
		public Dimension preferredLayoutSize(Container parent)
		{
			return size(parent, true);
		}

		@Override
		public Dimension minimumLayoutSize(Container parent)
		{
			return size(parent, false);
		}

		private Dimension size(Container parent, boolean preferred)
		{
			synchronized (parent.getTreeLock())
			{
				Insets in = parent.getInsets();
				int w = 0;
				int h = 0;
				int n = 0;
				for (Component c : parent.getComponents())
				{
					if (!c.isVisible())
					{
						continue;
					}
					Dimension d = preferred ? c.getPreferredSize() : c.getMinimumSize();
					w = Math.max(w, d.width);
					h += d.height;
					n++;
				}
				if (n > 1)
				{
					h += gap * (n - 1);
				}
				return new Dimension(w + in.left + in.right, h + in.top + in.bottom);
			}
		}

		@Override
		public void layoutContainer(Container parent)
		{
			synchronized (parent.getTreeLock())
			{
				Insets in = parent.getInsets();
				int x = in.left;
				int w = parent.getWidth() - in.left - in.right;
				int y = in.top;
				for (Component c : parent.getComponents())
				{
					if (!c.isVisible())
					{
						continue;
					}
					int h = c.getPreferredSize().height;
					c.setBounds(x, y, w, h);
					y += h + gap;
				}
			}
		}
	}
}
