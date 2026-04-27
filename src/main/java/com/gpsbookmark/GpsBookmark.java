package com.gpsbookmark;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import net.runelite.api.coords.WorldPoint;

/**
 * A user-saved world location.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GpsBookmark
{
	private String id;
	private String name;
	private String notes;
	private int x;
	private int y;
	private int plane;
	/**
	 * Identifier of the {@link GpsBookmarkFolder} this bookmark belongs to,
	 * or {@code null} if the bookmark lives at the top level of the sidebar.
	 */
	private String folderId;

	public GpsBookmark(String name, String notes, int x, int y, int plane)
	{
		this(UUID.randomUUID().toString(), name, notes, x, y, plane, null);
	}

	public GpsBookmark(String name, String notes, int x, int y, int plane, String folderId)
	{
		this(UUID.randomUUID().toString(), name, notes, x, y, plane, folderId);
	}

	public WorldPoint toWorldPoint()
	{
		return new WorldPoint(x, y, plane);
	}
}
