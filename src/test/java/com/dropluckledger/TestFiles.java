package com.dropluckledger;

import java.nio.file.Path;
import net.runelite.client.util.Filepath;

/**
 * Makes the {@link Filepath} that RuneLite would hand the plugin, for a folder the test owns.  RuneLite marks this way of
 * making one as unchecked because it can point anywhere, so it is only for tests.
 */
public final class TestFiles
{
	private TestFiles()
	{
	}

	public static Filepath root(Path folder)
	{
		return Filepath.Unchecked.getRooted(folder);
	}
}
