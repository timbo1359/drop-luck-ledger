package com.dropluckledger.plugin;

import com.dropluckledger.engine.Data;
import com.dropluckledger.engine.Data.Boss;
import com.dropluckledger.ledger.BossState;
import com.dropluckledger.ledger.Drop;
import java.text.NumberFormat;
import java.util.Locale;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;

/**
 * The game message shown when a drop has been added to the ledger.
 */
public final class DropMessages
{
	private DropMessages()
	{
	}

	/**
	 * "Logged Dragonbone necklace at Vorkath kill 1,234 (412 kills since the last one)."
	 *
	 * @param state the boss as it is after the drop was added
	 * @return the message in RuneLite's chat format
	 */
	public static String build(Data data, String bossId, BossState state, String item, int killNo)
	{
		Boss boss = data.boss(bossId);
		String bossName = boss == null ? bossId : boss.name;
		return new ChatMessageBuilder()
			.append(ChatColorType.NORMAL)
			.append("Logged ")
			.append(ChatColorType.HIGHLIGHT)
			.append(item)
			.append(ChatColorType.NORMAL)
			.append(" at ")
			.append(ChatColorType.HIGHLIGHT)
			.append(bossName)
			.append(ChatColorType.NORMAL)
			.append(" kill " + number(killNo) + detail(state, item, killNo) + ".")
			.build();
	}

	/**
	 * What to say about the streak: kills since the last copy, or that this is the first one.  Nothing when copies of unknown
	 * kill number (imported from history) make that unclear.
	 */
	static String detail(BossState state, String item, int killNo)
	{
		int previous = 0;
		int unknown = 0;
		int others = 0;
		for (Drop d : state.drops)
		{
			if (!d.item.equals(item))
			{
				continue;
			}
			if (d.kc == null)
			{
				unknown++;
			}
			else if (d.kc < killNo)
			{
				previous = Math.max(previous, d.kc);
				others++;
			}
			else if (d.kc > killNo)
			{
				return "";
			}
		}
		if (unknown > 0)
		{
			return "";
		}
		if (others == 0)
		{
			return killNo > 1 ? " (the first one, after " + number(killNo - 1) + " kills)" : "";
		}
		int since = killNo - previous;
		return " (" + number(since) + (since == 1 ? " kill" : " kills") + " since the last one)";
	}

	private static String number(int n)
	{
		return NumberFormat.getIntegerInstance(Locale.US).format(n);
	}
}
