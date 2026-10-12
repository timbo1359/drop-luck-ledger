package com.dropluckledger;

import com.dropluckledger.engine.Data;
import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;

/**
 * Loads the boss data once for all tests.
 */
public final class TestData
{
	private static Data data;

	private TestData()
	{
	}

	public static synchronized Data get()
	{
		if (data == null)
		{
			try (InputStream in = TestData.class.getResourceAsStream("/com/dropluckledger/data.json"))
			{
				data = Data.load(new Gson(), in);
			}
			catch (IOException ex)
			{
				throw new IllegalStateException(ex);
			}
		}
		return data;
	}
}
