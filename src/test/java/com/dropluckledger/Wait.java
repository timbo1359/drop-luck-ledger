package com.dropluckledger;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * Waits for something other threads are doing.
 */
public final class Wait
{
	private Wait()
	{
	}

	/**
	 * Waits until a condition is true.
	 *
	 * @throws AssertionError when it is not true after ten seconds
	 */
	public static void until(String what, BooleanSupplier condition)
	{
		until(what, 10, condition);
	}

	/**
	 * Waits until a condition is true.
	 *
	 * @throws AssertionError when it is not true after the given time
	 */
	public static void until(String what, int seconds, BooleanSupplier condition)
	{
		final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
		while (!condition.getAsBoolean())
		{
			if (System.nanoTime() > end)
			{
				throw new AssertionError("timed out waiting for " + what);
			}
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
		}
	}
}
