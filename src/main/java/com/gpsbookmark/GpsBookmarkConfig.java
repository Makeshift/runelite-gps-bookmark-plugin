package com.gpsbookmark;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;

/**
 * Configuration interface for the GPS Bookmark plugin.
 *
 * <p>Persistent bookmark data is not stored as a {@code @ConfigItem} (those are reserved for
 * user-tweakable settings shown in the RuneLite settings UI). Instead, the bookmark list is
 * stored as a JSON-serialized value under {@link #GROUP} / {@code bookmarks} via
 * {@link net.runelite.client.config.ConfigManager#setConfiguration(String, String, Object)}.
 */
@ConfigGroup(GpsBookmarkConfig.GROUP)
public interface GpsBookmarkConfig extends Config
{
	String GROUP = "gpsbookmark";
	String BOOKMARKS_KEY = "bookmarks";
}
