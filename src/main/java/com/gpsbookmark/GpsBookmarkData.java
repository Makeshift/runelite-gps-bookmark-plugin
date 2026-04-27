package com.gpsbookmark;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Container that bundles all GPS bookmark plugin state (folders and the
 * bookmarks they contain) into a single object. This is what gets persisted
 * to RuneLite's per-profile configuration as a single JSON blob, and is also
 * the natural unit for future import/export functionality.
 *
 * <p>Bookmarks reference their parent folder by {@link GpsBookmark#getFolderId()};
 * a {@code null} folder id means the bookmark lives at the top level.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GpsBookmarkData
{
	/**
	 * Schema version for the persisted document. Increment when introducing
	 * a backward-incompatible change so future loaders can migrate or refuse
	 * to read newer formats.
	 */
	public static final int CURRENT_VERSION = 1;

	private int version = CURRENT_VERSION;
	private List<GpsBookmarkFolder> folders = new ArrayList<>();
	private List<GpsBookmark> bookmarks = new ArrayList<>();

	public static GpsBookmarkData empty()
	{
		return new GpsBookmarkData(CURRENT_VERSION, new ArrayList<>(), new ArrayList<>());
	}
}
