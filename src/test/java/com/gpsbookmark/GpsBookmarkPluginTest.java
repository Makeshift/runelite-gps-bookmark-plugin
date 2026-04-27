package com.gpsbookmark;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class GpsBookmarkPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(GpsBookmarkPlugin.class);
		RuneLite.main(args);
	}
}
