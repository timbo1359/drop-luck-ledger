package com.dropluckledger.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * A heading that opens and closes the part under it.
 */
@SuppressWarnings("serial")
final class Fold extends Column
{
	private final JButton head;
	private final JComponent body;
	private final JComponent always;
	private final Consumer<Boolean> onToggle;
	private boolean open;

	Fold(String title, boolean open, Consumer<Boolean> onToggle, JComponent body)
	{
		this(title, open, onToggle, null, body);
	}

	/**
	 * @param always a part shown under the heading while the fold is closed, such as a summary of what is inside (may be null)
	 */
	Fold(String title, boolean open, Consumer<Boolean> onToggle, JComponent always, JComponent body)
	{
		super(4);
		this.onToggle = onToggle;
		this.body = body;
		this.always = always;
		head = new JButton(title);
		head.setFont(Look.body());
		head.setForeground(Look.TEXT);
		head.setBorderPainted(false);
		head.setContentAreaFilled(false);
		head.setFocusPainted(false);
		head.setHorizontalAlignment(SwingConstants.LEFT);
		head.setMargin(new java.awt.Insets(1, 0, 1, 0));
		head.setIconTextGap(6);
		head.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		head.addActionListener(e -> set(!this.open, true));
		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.add(head, BorderLayout.CENTER);
		add(top);
		if (always != null)
		{
			add(always);
		}
		add(body);
		set(open, false);
	}

	private void set(boolean open, boolean notify)
	{
		this.open = open;
		head.setIcon(Glyph.of(open ? Glyph.Kind.DOWN : Glyph.Kind.RIGHT));
		body.setVisible(open);
		if (always != null)
		{
			always.setVisible(!open);
		}
		revalidate();
		repaint();
		if (notify && onToggle != null)
		{
			onToggle.accept(open);
		}
	}

	boolean isOpen()
	{
		return open;
	}

	Component header()
	{
		return head;
	}
}
