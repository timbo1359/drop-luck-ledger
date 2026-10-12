package com.dropluckledger.ledger;

import java.io.IOException;

/**
 * Where one account's ledger file lives.
 */
public interface LedgerStore
{
	/**
	 * @return the saved text, or null when nothing has been saved yet
	 */
	String read() throws IOException;

	/**
	 * Replaces the saved text.  Must not leave a half-written file behind if it fails.
	 */
	void write(String json) throws IOException;

	/**
	 * Called when the saved text could not be understood: keep it somewhere (for example by renaming the file) so the next
	 * save does not destroy it.
	 */
	default void quarantine() throws IOException
	{
	}
}
