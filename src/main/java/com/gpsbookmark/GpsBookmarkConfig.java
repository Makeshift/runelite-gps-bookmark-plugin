package com.gpsbookmark;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(GpsBookmarkConfig.GROUP)
public interface GpsBookmarkConfig extends Config
{
	String GROUP = "gpsbookmark";

	/**
	 * Single config key under which the unified {@link GpsBookmarkData}
	 * document (folders + bookmarks) is persisted as JSON.
	 */
	String KEY_DATA = "data";

	/**
	 * Hidden config item used to persist the serialized {@link GpsBookmarkData}
	 * document. It is not shown in the plugin settings panel because no
	 * {@code @ConfigItem} annotated method exposes it visibly.
	 */
	@ConfigItem(
		keyName = KEY_DATA,
		name = "",
		description = "",
		hidden = true
	)
	default String dataJson()
	{
		return "";
	}
}
