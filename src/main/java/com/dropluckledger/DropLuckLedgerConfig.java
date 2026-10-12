package com.dropluckledger;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(DropLuckLedgerConfig.GROUP)
public interface DropLuckLedgerConfig extends Config
{
	String GROUP = "dropluckledger";

	@ConfigItem(
		keyName = "chatMessages",
		name = "Chat message for logged drops",
		description = "Shows a game message when a drop is added to your ledger, with the number of kills since the last copy",
		position = 1
	)
	default boolean chatMessages()
	{
		return true;
	}

	@ConfigItem(
		keyName = "importOnLogin",
		name = "Look for history on login",
		description = "When you log in to an account that has no ledger yet, check RuneLite's saved kill counts and Loot Tracker data and offer to import them",
		position = 2
	)
	default boolean importOnLogin()
	{
		return true;
	}
}
