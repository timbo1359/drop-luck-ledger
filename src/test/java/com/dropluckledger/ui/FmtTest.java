package com.dropluckledger.ui;

import static org.junit.Assert.assertEquals;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The formatting must print what the web app prints.  The expected texts were produced by the web app's own functions.
 */
public class FmtTest
{
	private static JsonObject fixture;

	@BeforeClass
	public static void load() throws IOException
	{
		try (InputStream in = FmtTest.class.getResourceAsStream("/fmt.json"))
		{
			fixture = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	private static double number(JsonElement e)
	{
		if (e.getAsJsonPrimitive().isString())
		{
			assertEquals("inf", e.getAsString());
			return Double.POSITIVE_INFINITY;
		}
		return e.getAsDouble();
	}

	@Test
	public void numbersMatchTheWebApp()
	{
		JsonArray rows = fixture.getAsJsonArray("fmt");
		for (JsonElement r : rows)
		{
			JsonArray row = r.getAsJsonArray();
			double in = number(row.get(0));
			assertEquals("fmt(" + in + ")", row.get(1).getAsString(), Fmt.num(in));
		}
	}

	@Test
	public void percentagesMatchTheWebApp()
	{
		JsonArray rows = fixture.getAsJsonArray("pct");
		for (JsonElement r : rows)
		{
			JsonArray row = r.getAsJsonArray();
			JsonElement in = row.get(0);
			Double p;
			if (in.getAsJsonPrimitive().isString())
			{
				p = "nan".equals(in.getAsString()) ? Double.NaN : null;
			}
			else
			{
				p = in.getAsDouble();
			}
			assertEquals("pctStr(" + in + ")", row.get(1).getAsString(), Fmt.pct(p));
		}
	}

	@Test
	public void percentagesInMarkupKeepTheirSigns()
	{
		assertEquals("&lt;0.1%", Fmt.pctHtml(0.0004));
		assertEquals("&gt;99.9%", Fmt.pctHtml(0.9999));
		assertEquals("38%", Fmt.pctHtml(0.38));
		assertEquals("<0.1%", Swings.plain(Look.html(100, "x " + Fmt.pctHtml(0.0004))).replace("x ", ""));
	}

	@Test
	public void ratesMatchTheWebApp()
	{
		JsonArray rows = fixture.getAsJsonArray("rate");
		for (JsonElement r : rows)
		{
			JsonArray row = r.getAsJsonArray();
			JsonElement in = row.get(0);
			double p = in.getAsJsonPrimitive().isString() ? 0 : in.getAsDouble();
			assertEquals("rateStr(" + in + ")", row.get(1).getAsString(), Fmt.rate(p));
		}
	}

	@Test
	public void expectedCopiesUseTwoDecimalsUnderTen()
	{
		assertEquals("0.42", Fmt.expected(0.4213));
		assertEquals("9.99", Fmt.expected(9.994));
		assertEquals("12.3", Fmt.expected(12.34));
	}
}
