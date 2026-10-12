package com.dropluckledger.ledger;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps a {@link LedgerStore} up to date: changes are written a moment after the last one, on a background thread.
 */
public final class LedgerSaver implements Ledger.Listener
{
	private static final Logger log = LoggerFactory.getLogger(LedgerSaver.class);

	private final Ledger ledger;
	private final Gson gson;
	private final ScheduledExecutorService executor;
	private final long delayMs;
	private LedgerStore store;
	private Future<?> pending;
	private boolean dirty;
	private boolean failedOnce;

	public LedgerSaver(Ledger ledger, Gson gson, ScheduledExecutorService executor, long delayMs)
	{
		this.ledger = ledger;
		this.gson = gson;
		this.executor = executor;
		this.delayMs = delayMs;
	}

	/**
	 * Where to save from now on (null = nowhere, for example while logged out).  Anything not written yet goes to the
	 * previous place first, so to switch accounts call setStore(null), load the other account into the ledger, then
	 * setStore(its store).
	 */
	public synchronized void setStore(LedgerStore next)
	{
		flushNow();
		store = next;
		dirty = false;
	}

	@Override
	public synchronized void changed(String bossId)
	{
		if (store == null)
		{
			return;
		}
		dirty = true;
		if (pending != null)
		{
			pending.cancel(false);
		}
		pending = executor.schedule(this::flushNow, delayMs, TimeUnit.MILLISECONDS);
	}

	/**
	 * Writes now if there is anything to write.  Safe to call from any thread.
	 */
	public synchronized void flushNow()
	{
		if (pending != null)
		{
			pending.cancel(false);
			pending = null;
		}
		if (store == null || !dirty)
		{
			return;
		}
		try
		{
			store.write(LedgerCodec.toJson(gson, ledger));
			dirty = false;
			failedOnce = false;
		}
		catch (IOException | RuntimeException ex)
		{
			if (!failedOnce)
			{
				log.warn("Could not save the drop ledger", ex);
				failedOnce = true;
			}
			// keep it dirty; the next change tries again
		}
	}

	public synchronized void cancel()
	{
		if (pending != null)
		{
			pending.cancel(false);
			pending = null;
		}
	}

	/**
	 * Reads the saved ledger into the live one.  A missing file leaves the ledger empty.
	 *
	 * @return false when a file existed but could not be read (the ledger is left empty and nothing is overwritten
	 * until the player changes something)
	 */
	public synchronized boolean loadInto(LedgerStore from)
	{
		try
		{
			String text = from.read();
			if (text == null || text.trim().isEmpty())
			{
				ledger.replaceWith(new Ledger(ledger.data()));
				return true;
			}
			ledger.replaceWith(LedgerCodec.fromJson(ledger.data(), text));
			return true;
		}
		catch (IOException | LedgerCodec.FormatException | RuntimeException ex)
		{
			log.warn("Could not read the saved drop ledger", ex);
			try
			{
				from.quarantine();
			}
			catch (IOException | RuntimeException ex2)
			{
				log.warn("Could not set the unreadable ledger file aside", ex2);
			}
			ledger.replaceWith(new Ledger(ledger.data()));
			return false;
		}
	}
}
