package com.dropluckledger.ui;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JComponent;

/**
 * Stands in for the panel's windows: records what was asked and answers as the test says.
 */
final class FakeDialogs implements Dialogs
{
	final List<String> asked = new ArrayList<>();
	final List<String> messages = new ArrayList<>();
	boolean formAnswer = true;
	boolean confirmAnswer = true;
	int chooseAnswer = 0;
	/**
	 * Runs on a form before the "player" closes it, to fill it in.
	 */
	Consumer<JComponent> fill = f ->
	{
	};
	JComponent lastForm;
	String lastTitle;

	@Override
	public boolean form(Component parent, String title, JComponent form, String ok)
	{
		asked.add("form:" + title);
		lastForm = form;
		lastTitle = title;
		fill.accept(form);
		return formAnswer;
	}

	@Override
	public boolean confirm(Component parent, String title, String message, String yes)
	{
		asked.add("confirm:" + title);
		messages.add(message);
		return confirmAnswer;
	}

	@Override
	public int choose(Component parent, String title, String message, String... options)
	{
		asked.add("choose:" + title);
		messages.add(message);
		return chooseAnswer;
	}

	@Override
	public void note(Component parent, String title, String message)
	{
		asked.add("note:" + title);
		messages.add(message);
	}

	String last()
	{
		return messages.isEmpty() ? "" : messages.get(messages.size() - 1);
	}
}
