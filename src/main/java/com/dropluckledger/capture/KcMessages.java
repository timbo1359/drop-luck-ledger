package com.dropluckledger.capture;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises the game messages the plugin cares about.  The patterns follow the ones RuneLite's own Chat Commands plugin uses
 * for kill counts, so they match the same wording.
 */
public final class KcMessages
{
	private KcMessages()
	{
	}

	private static final Pattern KILLCOUNT = Pattern.compile("Your (?<pre>completion count for |subdued |completed )?(?:<col=[0-9a-f]{6}>)?(?<boss>.+?)(?:</col>)? (?<post>(?:(?:kill|harvest|lap|completion|success|Total Ticket) )?(?:count )?)is: ?(?:<col=[0-9a-f]{6}>|@.+?@)(?<kc>[0-9,]+)</col>");
	private static final Pattern COLLECTION_LOG = Pattern.compile("New item added to your collection log: (.*)");
	private static final String[] PET = {
		"You have a funny feeling like you're being followed",
		"You feel something weird sneaking into your backpack",
		"You have a funny feeling like you would have been followed",
	};

	/**
	 * A kill-count message: the text the game used for the activity and the count it shows.
	 */
	public static final class Kc
	{
		public final String label;
		public final int count;

		Kc(String label, int count)
		{
			this.label = label;
			this.count = count;
		}
	}

	/**
	 * @return the label and count, or null when the message is not a kill-count message
	 */
	public static Kc parseKillCount(String message)
	{
		if (message == null)
		{
			return null;
		}
		Matcher m = KILLCOUNT.matcher(message);
		if (!m.find())
		{
			return null;
		}
		String pre = m.group("pre");
		String post = m.group("post");
		if ((pre == null || pre.isEmpty()) && (post == null || post.isEmpty()))
		{
			return null;
		}
		String label = m.group("boss").replace(":", "");
		int n;
		try
		{
			n = Integer.parseInt(m.group("kc").replace(",", ""));
		}
		catch (NumberFormatException ex)
		{
			return null;
		}
		return new Kc(label, n);
	}

	/**
	 * @return the item name of a "New item added to your collection log" message, or null
	 */
	public static String collectionLogItem(String message)
	{
		if (message == null)
		{
			return null;
		}
		Matcher m = COLLECTION_LOG.matcher(stripTags(message));
		return m.find() ? m.group(1).trim() : null;
	}

	public static boolean isPetMessage(String message)
	{
		if (message == null)
		{
			return false;
		}
		String plain = stripTags(message);
		for (String p : PET)
		{
			if (plain.contains(p))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The message without the colour tags the game puts in it.
	 */
	public static String stripTags(String message)
	{
		return message.replaceAll("<[^>]*>", "");
	}
}
