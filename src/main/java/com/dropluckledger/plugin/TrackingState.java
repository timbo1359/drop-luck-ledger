package com.dropluckledger.plugin;

/**
 * Whether the plugin is recording at the moment, and if not why not (shown at the top of the side panel).
 */
public enum TrackingState
{
	/**
	 * The boss data is still being read.
	 */
	STARTING("Loading the boss data..."),
	/**
	 * Nobody is logged in, so there is no ledger to show.
	 */
	LOGGED_OUT("Log in to see and record your ledger."),
	/**
	 * Logged in on a world that has its own separate save (a Leagues, Deadman, tournament or beta world).
	 */
	PAUSED("Paused: this kind of world has its own save, so it is not recorded in your ledger."),
	/**
	 * Logged in and the ledger is being read from disk.
	 */
	LOADING("Loading your ledger..."),
	/**
	 * Recording.
	 */
	TRACKING("");

	private final String message;

	TrackingState(String message)
	{
		this.message = message;
	}

	/**
	 * What to tell the player; empty while recording.
	 */
	public String message()
	{
		return message;
	}
}
