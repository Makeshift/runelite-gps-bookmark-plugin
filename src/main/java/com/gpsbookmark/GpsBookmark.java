package com.gpsbookmark;

import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;
import net.runelite.api.coords.WorldPoint;

/**
 * A user-defined bookmark for a {@link WorldPoint} location.
 */
@Data
@NoArgsConstructor
public class GpsBookmark
{
	private String id;
	private String name;
	private String notes;
	private int x;
	private int y;
	private int plane;

	public GpsBookmark(String id, String name, String notes, int x, int y, int plane)
	{
		this.id = id;
		this.name = name;
		this.notes = notes;
		this.x = x;
		this.y = y;
		this.plane = plane;
	}

	public static GpsBookmark create(String name, String notes, int x, int y, int plane)
	{
		return new GpsBookmark(UUID.randomUUID().toString(), name, notes, x, y, plane);
	}

	public WorldPoint toWorldPoint()
	{
		return new WorldPoint(x, y, plane);
	}
}
