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

	public GpsBookmark(String name, String notes, int x, int y, int plane)
	{
		this(UUID.randomUUID().toString(), name, notes, x, y, plane);
	}

	public WorldPoint toWorldPoint()
	{
		return new WorldPoint(x, y, plane);
	}
}
