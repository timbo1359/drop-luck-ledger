package com.dropluckledger.ui;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Number formatting for the panel.  It prints exactly what the web app prints for the same number.
 */
final class Fmt
{
	private static final String[] SUFFIX = {"th", "st", "nd", "rd"};

	private Fmt()
	{
	}

	/**
	 * Like JavaScript's toFixed: the exact value of the number, rounded half up.
	 */
	static String fixed(double x, int digits)
	{
		return new BigDecimal(x).setScale(digits, RoundingMode.HALF_UP).toPlainString();
	}

	/**
	 * A whole number with thousands separators ("1,234"); the infinity sign for an endless wait.
	 */
	static String num(double n)
	{
		if (Double.isNaN(n))
		{
			return "NaN";
		}
		if (Double.isInfinite(n))
		{
			return n > 0 ? "∞" : "-∞";
		}
		return String.format(Locale.US, "%,d", Math.round(n));
	}

	/**
	 * A chance as a percentage: "0%", "<0.1%", "4.5%", "38%", "99.6%", ">99.9%"; a dash when there is none.
	 */
	static String pct(Double p)
	{
		if (p == null || Double.isNaN(p))
		{
			return "—";
		}
		if (p <= 0)
		{
			return "0%";
		}
		if (p < 0.001)
		{
			return "<0.1%";
		}
		if (p > 0.9995)
		{
			return ">99.9%";
		}
		if (p < 0.1 || p > 0.99)
		{
			return fixed(p * 100, 1) + "%";
		}
		return Math.round(p * 100) + "%";
	}

	/**
	 * A per-kill chance as "1/128", "every kill" or a dash.
	 */
	static String rate(double p)
	{
		if (!(p > 0))
		{
			return "—";
		}
		double x = 1 / p;
		if (x < 1.05)
		{
			return "every kill";
		}
		if (x < 10)
		{
			return "1/" + fixed(x, 2).replaceAll("\\.?0+$", "");
		}
		if (x < 100)
		{
			return "1/" + fixed(x, 1).replaceAll("\\.0$", "");
		}
		return "1/" + num(x);
	}

	/**
	 * "1st", "22nd", "99th": a percentile, kept between 1 and 99.
	 */
	static String ord(double n)
	{
		int v = (int) Math.min(99, Math.max(1, Math.round(n)));
		int m = v % 100;
		int k = m - 20;
		String s;
		if (k >= 0 && k % 10 >= 1 && k % 10 <= 3)
		{
			s = SUFFIX[k % 10];
		}
		else if (m >= 1 && m <= 3)
		{
			s = SUFFIX[m];
		}
		else
		{
			s = SUFFIX[0];
		}
		return v + s;
	}

	/**
	 * A count of expected copies: two decimals under 10, one above.
	 */
	static String expected(double e)
	{
		return e < 10 ? fixed(e, 2) : fixed(e, 1);
	}
}
