package com.gpsbookmark;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A user-defined folder used to group {@link GpsBookmark} entries together
 * into a collapsible section in the sidebar.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GpsBookmarkFolder
{
	private String id;
	private String name;
	private boolean collapsed;

	public GpsBookmarkFolder(String name)
	{
		this(UUID.randomUUID().toString(), name, false);
	}
}
