package com.dropluckledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.dropluckledger.engine.Data;
import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import org.junit.Test;

/**
 * What ships inside the plugin jar.
 */
public class ResourcesTest
{
	private static byte[] read(String path) throws IOException
	{
		try (InputStream in = ResourcesTest.class.getResourceAsStream(path))
		{
			assertNotNull(path + " is missing", in);
			return in.readAllBytes();
		}
	}

	@Test
	public void theToolbarIconIsASmallRealPng() throws IOException
	{
		byte[] png = read("/com/dropluckledger/icon.png");
		// the PNG signature: a renamed JPEG or ICO would not have it
		assertEquals(0x89, png[0] & 0xff);
		assertEquals('P', png[1]);
		assertEquals('N', png[2]);
		assertEquals('G', png[3]);
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
		assertNotNull(image);
		// Java keeps every pixel at four bytes in memory, so a toolbar icon stays small
		assertTrue(image.getWidth() + "x" + image.getHeight(), image.getWidth() <= 32 && image.getHeight() <= 32);
		assertTrue("icon is " + png.length + " bytes", png.length < 2048);
	}

	@Test
	public void theBossDataIsInThePlugin() throws IOException
	{
		try (InputStream in = ResourcesTest.class.getResourceAsStream("/com/dropluckledger/data.json"))
		{
			assertNotNull(in);
			Data data = Data.load(new Gson(), in);
			// the web page's bosses but the raids, the Royal Titans and Wintertodt (src/plugin-exclude.txt in the web project)
			assertEquals(64, data.bosses.size());
			for (String left : new String[]{"chambers-of-xeric", "theatre-of-blood", "tombs-of-amascut", "royal-titans", "wintertodt"})
			{
				assertNull(left, data.boss(left));
			}
		}
	}
}
