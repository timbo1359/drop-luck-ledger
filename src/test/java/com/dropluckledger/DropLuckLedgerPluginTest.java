package com.dropluckledger;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class DropLuckLedgerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(DropLuckLedgerPlugin.class);
		RuneLite.main(args);
	}
}
