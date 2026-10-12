package com.dropluckledger.capture;

import java.util.Locale;

/**
 * Name matching helpers: game text and the boss data spell the same thing slightly differently
 * ("The Nightmare" / "Nightmare", "Theatre of Blood: Hard Mode" / "Theatre of Blood Hard Mode").
 */
public final class Names
{
	private Names()
	{
	}

	/**
	 * Lower case, letters and digits only, a leading "the" dropped.
	 */
	public static String norm(String s)
	{
		if (s == null)
		{
			return "";
		}
		String t = flat(s.replace("&", "and"));
		if (t.startsWith("the") && t.length() > 3)
		{
			t = t.substring(3);
		}
		return t;
	}

	/**
	 * Lower case, letters and digits only.
	 */
	public static String flat(String s)
	{
		if (s == null)
		{
			return "";
		}
		StringBuilder b = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++)
		{
			char c = Character.toLowerCase(s.charAt(i));
			if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))
			{
				b.append(c);
			}
		}
		return b.toString().toLowerCase(Locale.ROOT);
	}
}
