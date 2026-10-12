package com.dropluckledger.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

/**
 * Helpers for looking at and driving Swing components in tests.
 */
final class Swings
{
	private Swings()
	{
	}

	static void edt(Runnable r)
	{
		if (SwingUtilities.isEventDispatchThread())
		{
			r.run();
			return;
		}
		try
		{
			SwingUtilities.invokeAndWait(r);
		}
		catch (InvocationTargetException ex)
		{
			Throwable c = ex.getCause();
			if (c instanceof RuntimeException)
			{
				throw (RuntimeException) c;
			}
			if (c instanceof Error)
			{
				throw (Error) c;
			}
			throw new AssertionError(c);
		}
		catch (InterruptedException ex)
		{
			Thread.currentThread().interrupt();
			throw new AssertionError(ex);
		}
	}

	static <T> T get(Supplier<T> s)
	{
		AtomicReference<T> out = new AtomicReference<>();
		edt(() -> out.set(s.get()));
		return out.get();
	}

	static <T extends Component> List<T> all(Component root, Class<T> type)
	{
		List<T> out = new ArrayList<>();
		collect(root, type, out);
		return out;
	}

	private static <T extends Component> void collect(Component c, Class<T> type, List<T> out)
	{
		if (type.isInstance(c))
		{
			out.add(type.cast(c));
		}
		if (c instanceof Container)
		{
			for (Component k : ((Container) c).getComponents())
			{
				collect(k, type, out);
			}
		}
	}

	static <T extends Component> T first(Component root, Class<T> type, Predicate<? super T> test)
	{
		for (T c : all(root, type))
		{
			if (test.test(c))
			{
				return c;
			}
		}
		return null;
	}

	/**
	 * The visible words of some markup.
	 */
	static String plain(String html)
	{
		if (html == null)
		{
			return "";
		}
		return html.replaceAll("<[^>]*>", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replaceAll("\\s+", " ").trim();
	}

	/**
	 * All the text shown by labels and buttons under a component.
	 */
	static String text(Component root)
	{
		StringBuilder b = new StringBuilder();
		for (Component c : all(root, Component.class))
		{
			String t = null;
			if (c instanceof JLabel)
			{
				t = ((JLabel) c).getText();
			}
			else if (c instanceof AbstractButton)
			{
				t = ((AbstractButton) c).getText();
			}
			if (t != null && !t.isEmpty())
			{
				b.append(plain(t)).append(" | ");
			}
		}
		return b.toString();
	}

	static JLabel label(Component root, String part)
	{
		return first(root, JLabel.class, l -> plain(l.getText()).contains(part));
	}

	static JButton button(Component root, String part)
	{
		JButton b = first(root, JButton.class, x -> x.getText() != null && x.getText().contains(part));
		if (b == null)
		{
			b = first(root, JButton.class, x -> x.getToolTipText() != null && x.getToolTipText().contains(part));
		}
		return b;
	}

	/**
	 * Clicks like the mouse: the nearest component at or above the one given that has a mouse listener.
	 */
	static void click(Component c)
	{
		for (Component k = c; k != null; k = k.getParent())
		{
			MouseListener[] ls = k.getMouseListeners();
			if (ls.length > 0)
			{
				MouseEvent e = new MouseEvent(k, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 3, 3, 1, false);
				for (MouseListener l : ls)
				{
					l.mouseClicked(e);
				}
				return;
			}
		}
		throw new AssertionError("nothing to click at " + c);
	}
}
