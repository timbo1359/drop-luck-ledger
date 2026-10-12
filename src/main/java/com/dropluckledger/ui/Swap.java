package com.dropluckledger.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Point;
import java.util.List;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;

/**
 * Replaces what a container shows without making the scroll pane jump.
 */
final class Swap
{
	private Swap()
	{
	}

	/**
	 * Puts new parts into a container and keeps the scroll position where it was (as far as the new height allows).
	 */
	static void into(Container holder, List<? extends Component> parts)
	{
		final JViewport port = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, holder);
		final Point where = port == null ? null : port.getViewPosition();
		holder.removeAll();
		for (Component c : parts)
		{
			holder.add(c);
		}
		holder.revalidate();
		holder.repaint();
		if (port != null && where != null && where.y > 0)
		{
			SwingUtilities.invokeLater(() ->
			{
				Dimension view = port.getViewSize();
				int max = Math.max(0, view.height - port.getExtentSize().height);
				port.setViewPosition(new Point(0, Math.min(where.y, max)));
			});
		}
	}
}
