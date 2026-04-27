package com.gpsbookmark;

import com.google.gson.Gson;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GpsBookmarkManagerTest
{
	private ConfigManager configManager;
	private Map<String, String> store;
	private GpsBookmarkManager manager;

	@Before
	public void setUp()
	{
		store = new HashMap<>();
		configManager = mock(ConfigManager.class);

		when(configManager.getConfiguration(eq(GpsBookmarkConfig.GROUP), eq(GpsBookmarkConfig.BOOKMARKS_KEY)))
			.thenAnswer(inv -> store.get(GpsBookmarkConfig.BOOKMARKS_KEY));

		doAnswer(inv ->
		{
			Object value = inv.getArgument(2);
			store.put(GpsBookmarkConfig.BOOKMARKS_KEY, value == null ? null : value.toString());
			return null;
		}).when(configManager).setConfiguration(eq(GpsBookmarkConfig.GROUP), eq(GpsBookmarkConfig.BOOKMARKS_KEY), any());

		manager = new GpsBookmarkManager(configManager, new Gson());
	}

	@Test
	public void emptyOnFirstLoad()
	{
		assertTrue(manager.getBookmarks().isEmpty());
	}

	@Test
	public void addPersistsAndReloads()
	{
		GpsBookmark bm = GpsBookmark.create("Lumbridge", "Spawn", 3222, 3218, 0);
		manager.addBookmark(bm);

		// Fresh manager simulating restart -> reads JSON from the same store.
		GpsBookmarkManager reloaded = new GpsBookmarkManager(configManager, new Gson());
		List<GpsBookmark> bookmarks = reloaded.getBookmarks();
		assertEquals(1, bookmarks.size());
		GpsBookmark loaded = bookmarks.get(0);
		assertEquals(bm.getId(), loaded.getId());
		assertEquals("Lumbridge", loaded.getName());
		assertEquals("Spawn", loaded.getNotes());
		assertEquals(3222, loaded.getX());
		assertEquals(3218, loaded.getY());
		assertEquals(0, loaded.getPlane());
	}

	@Test
	public void updateReplacesById()
	{
		GpsBookmark bm = GpsBookmark.create("A", "n", 1, 2, 0);
		manager.addBookmark(bm);

		GpsBookmark updated = new GpsBookmark(bm.getId(), "A2", "n2", 10, 20, 1);
		assertTrue(manager.updateBookmark(updated));

		List<GpsBookmark> bookmarks = manager.getBookmarks();
		assertEquals(1, bookmarks.size());
		assertEquals("A2", bookmarks.get(0).getName());
		assertEquals(10, bookmarks.get(0).getX());
		assertEquals(1, bookmarks.get(0).getPlane());
	}

	@Test
	public void updateUnknownIdReturnsFalse()
	{
		assertFalse(manager.updateBookmark(GpsBookmark.create("ghost", "", 0, 0, 0)));
	}

	@Test
	public void removeDeletesById()
	{
		GpsBookmark a = GpsBookmark.create("A", "", 1, 1, 0);
		GpsBookmark b = GpsBookmark.create("B", "", 2, 2, 0);
		manager.addBookmark(a);
		manager.addBookmark(b);

		assertTrue(manager.removeBookmark(a.getId()));
		List<GpsBookmark> bookmarks = manager.getBookmarks();
		assertEquals(1, bookmarks.size());
		assertEquals(b.getId(), bookmarks.get(0).getId());
	}

	@Test
	public void invalidJsonFallsBackToEmpty()
	{
		store.put(GpsBookmarkConfig.BOOKMARKS_KEY, "{not valid json");
		GpsBookmarkManager fresh = new GpsBookmarkManager(configManager, new Gson());
		List<GpsBookmark> bookmarks = fresh.getBookmarks();
		assertNotNull(bookmarks);
		assertTrue(bookmarks.isEmpty());
	}

	@Test
	public void changeListenerFiresOnMutation()
	{
		final int[] calls = {0};
		manager.addChangeListener(() -> calls[0]++);
		manager.addBookmark(GpsBookmark.create("A", "", 0, 0, 0));
		assertEquals(1, calls[0]);
	}
}
