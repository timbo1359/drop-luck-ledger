package com.dropluckledger.ui;

import java.awt.Component;
import javax.swing.JComponent;
import javax.swing.JOptionPane;

/**
 * The questions the panel asks in windows of their own.  A separate type so that the panel can be tried without a screen.
 */
interface Dialogs
{
	/**
	 * Shows a form with an OK and a Cancel button.
	 *
	 * @return true when OK was chosen
	 */
	boolean form(Component parent, String title, JComponent form, String ok);

	/**
	 * A question with a yes and a no.
	 */
	boolean confirm(Component parent, String title, String message, String yes);

	/**
	 * A question with several answers.
	 *
	 * @return the index of the chosen option, or -1 when the window was closed
	 */
	int choose(Component parent, String title, String message, String... options);

	/**
	 * A note with an OK button.
	 */
	void note(Component parent, String title, String message);

	/**
	 * The real windows.
	 */
	final class Swing implements Dialogs
	{
		@Override
		public boolean form(Component parent, String title, JComponent form, String ok)
		{
			Object[] options = {ok, "Cancel"};
			int r = JOptionPane.showOptionDialog(parent, form, title, JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
			return r == 0;
		}

		@Override
		public boolean confirm(Component parent, String title, String message, String yes)
		{
			Object[] options = {yes, "Cancel"};
			int r = JOptionPane.showOptionDialog(parent, message, title, JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
			return r == 0;
		}

		@Override
		public int choose(Component parent, String title, String message, String... options)
		{
			return JOptionPane.showOptionDialog(parent, message, title, JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
		}

		@Override
		public void note(Component parent, String title, String message)
		{
			JOptionPane.showMessageDialog(parent, message, title, JOptionPane.INFORMATION_MESSAGE);
		}
	}
}
