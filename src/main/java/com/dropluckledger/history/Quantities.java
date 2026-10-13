package com.dropluckledger.history;

import com.dropluckledger.engine.Data.Boss;
import com.dropluckledger.engine.Data.Row;

/**
 * Turns "how many of an item the game says you have" into "how many times it dropped".
 * <p>
 * Stackable items drop several at a time (the Alchemical Hydra gives 500-1000 dragon knives, the Hueycoatl 1-20 huasca seeds), but
 * the Loot Tracker and the Collection Log only keep the running total.  Counting that total as separate drops would make
 * the player look absurdly lucky, so it is divided by the usual amount per drop.  The result is an estimate; for items that
 * always drop one at a time it is exact.
 */
public final class Quantities
{
	private Quantities()
	{
	}

	/**
	 * The usual number of the item in one drop from the boss: the average of the amounts on its drop table, weighted by how
	 * likely each row is; 1 when the table gives no amount.
	 */
	public static double perDrop(Boss boss, String item)
	{
		double sum = 0;
		double weights = 0;
		boolean allHaveRates = true;
		for (Row r : boss.allRows())
		{
			if (item.equals(r.name) && parse(r.qty) != null && !(r.p != null && r.p > 0))
			{
				allHaveRates = false;
			}
		}
		for (Row r : boss.allRows())
		{
			Double q = item.equals(r.name) ? parse(r.qty) : null;
			if (q == null)
			{
				continue;
			}
			double w = allHaveRates ? r.p : 1;
			sum += q * w;
			weights += w;
		}
		return weights > 0 ? Math.max(1, sum / weights) : 1;
	}

	/**
	 * How many drops a received total stands for.
	 *
	 * @param total how many of the item the game says were received
	 */
	public static int drops(Boss boss, String item, int total)
	{
		if (total <= 0)
		{
			return 0;
		}
		double each = perDrop(boss, item);
		if (each < 1.05)
		{
			return total;
		}
		return (int) Math.max(1, Math.round(total / each));
	}

	/**
	 * The amount a drop-table cell stands for: "3" is 3, "35-40" is 37.5, "11;17" (without and with a perfect kill) is 11, the
	 * trailing "n" of a noted item and the thousands separators are ignored.  Null when there is no amount.
	 */
	static Double parse(String qty)
	{
		if (qty == null)
		{
			return null;
		}
		String s = qty.trim().replace(",", "");
		int semicolon = s.indexOf(';');
		if (semicolon >= 0)
		{
			s = s.substring(0, semicolon);
		}
		s = s.replaceAll("[^0-9-]", "");
		if (s.isEmpty() || s.startsWith("-"))
		{
			return null;
		}
		try
		{
			int dash = s.indexOf('-');
			if (dash < 0)
			{
				return Double.valueOf(Long.parseLong(s));
			}
			double lo = Long.parseLong(s.substring(0, dash));
			double hi = Long.parseLong(s.substring(dash + 1));
			return (lo + hi) / 2;
		}
		catch (NumberFormatException ex)
		{
			return null;
		}
	}
}
