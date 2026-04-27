package com.gpsbookmark;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(GpsBookmarkConfig.GROUP)
public interface GpsBookmarkConfig extends Config
{
	String GROUP = "gpsbookmark";
	String KEY_BOOKMARKS = "bookmarks";

	/**
	 * Hidden config item used to persist the serialized list of bookmarks.
	 * It is not shown in the plugin settings panel because there is no
	 * {@code @ConfigItem} annotated method exposing it.
	 */
	@ConfigItem(
		keyName = KEY_BOOKMARKS,
		name = "",
		description = "",
		hidden = true
	)
	default String bookmarksJson()
	{
		return "";
	}
}
