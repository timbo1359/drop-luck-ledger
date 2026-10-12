package com.dropluckledger.ui;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the slow part of showing something away from the Swing thread and brings the result back to it.
 */
final class Jobs
{
	private static final Logger log = LoggerFactory.getLogger(Jobs.class);

	private final ExecutorService executor;
	private final AtomicInteger running = new AtomicInteger();
	private volatile boolean closed;

	Jobs(ExecutorService executor)
	{
		this.executor = executor;
	}

	/**
	 * True when nothing is being worked out and nothing is waiting to be shown.
	 */
	boolean idle()
	{
		return running.get() == 0;
	}

	void close()
	{
		closed = true;
	}

	/**
	 * One place for the newest result of one kind of work.  Starting new work makes an older, unfinished piece of work
	 * stale: it is asked to stop and its result is dropped.
	 */
	final class Slot<T>
	{
		private volatile int generation;

		/**
		 * Call on the Swing thread.
		 *
		 * @param work  runs on the background thread; given a test for "no longer wanted"; may return null
		 * @param shown runs on the Swing thread with a result that is still wanted
		 */
		void run(Function<BooleanSupplier, T> work, Consumer<T> shown)
		{
			if (closed)
			{
				return;
			}
			final int mine = ++generation;
			running.incrementAndGet();
			try
			{
				executor.execute(() ->
				{
					T result = null;
					try
					{
						result = work.apply(() -> mine != generation || closed);
					}
					catch (RuntimeException ex)
					{
						log.warn("Could not work out what to show", ex);
					}
					final T out = result;
					try
					{
						SwingUtilities.invokeLater(() ->
						{
							try
							{
								if (mine == generation && !closed && out != null)
								{
									shown.accept(out);
								}
							}
							catch (RuntimeException ex)
							{
								log.warn("Could not show the result", ex);
							}
							finally
							{
								running.decrementAndGet();
							}
						});
					}
					catch (RuntimeException ex)
					{
						running.decrementAndGet();
					}
				});
			}
			catch (RejectedExecutionException ex)
			{
				running.decrementAndGet();
			}
		}

		/**
		 * Drops whatever is still being worked out.
		 */
		void cancel()
		{
			generation++;
		}
	}

	<T> Slot<T> slot()
	{
		return new Slot<>();
	}
}
