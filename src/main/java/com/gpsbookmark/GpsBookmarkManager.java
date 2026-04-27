package com.gpsbookmark;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Loads and persists the user's saved {@link GpsBookmark}s as a JSON list under the
 * {@link GpsBookmarkConfig#GROUP} config group.
 */
@Slf4j
@Singleton
public class GpsBookmarkManager
{
	private static final Type LIST_TYPE = new TypeToken<List<GpsBookmark>>() {}.getType();

	private final ConfigManager configManager;
	private final Gson gson;

	private List<GpsBookmark> bookmarks;
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

	@Inject
	public GpsBookmarkManager(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
	}

	/**
	 * Returns an unmodifiable snapshot of the current bookmark list.
	 */
	public synchronized List<GpsBookmark> getBookmarks()
	{
		ensureLoaded();
		return Collections.unmodifiableList(new ArrayList<>(bookmarks));
	}

	public synchronized void addBookmark(GpsBookmark bookmark)
	{
		ensureLoaded();
		bookmarks.add(bookmark);
		persist();
		notifyListeners();
	}

	/**
	 * Updates the bookmark with the matching id. Returns {@code true} if a bookmark was
	 * found and replaced.
	 */
	public synchronized boolean updateBookmark(GpsBookmark updated)
	{
		ensureLoaded();
		for (int i = 0; i < bookmarks.size(); i++)
		{
			if (bookmarks.get(i).getId().equals(updated.getId()))
			{
				bookmarks.set(i, updated);
				persist();
				notifyListeners();
				return true;
			}
		}
		return false;
	}

	public synchronized boolean removeBookmark(String id)
	{
		ensureLoaded();
		boolean removed = bookmarks.removeIf(b -> b.getId().equals(id));
		if (removed)
		{
			persist();
			notifyListeners();
		}
		return removed;
	}

	public void addChangeListener(Runnable listener)
	{
		listeners.add(listener);
	}

	public void removeChangeListener(Runnable listener)
	{
		listeners.remove(listener);
	}

	private void ensureLoaded()
	{
		if (bookmarks != null)
		{
			return;
		}
		bookmarks = load();
	}

	private List<GpsBookmark> load()
	{
		final String json = configManager.getConfiguration(GpsBookmarkConfig.GROUP, GpsBookmarkConfig.BOOKMARKS_KEY);
		if (json == null || json.isEmpty())
		{
			return new ArrayList<>();
		}
		try
		{
			List<GpsBookmark> parsed = gson.fromJson(json, LIST_TYPE);
			if (parsed == null)
			{
				return new ArrayList<>();
			}
			// Defensive copy to a mutable ArrayList.
			return new ArrayList<>(parsed);
		}
		catch (JsonSyntaxException e)
		{
			log.warn("Failed to parse stored GPS bookmarks JSON; falling back to empty list", e);
			return new ArrayList<>();
		}
	}

	private void persist()
	{
		final String json = gson.toJson(bookmarks, LIST_TYPE);
		configManager.setConfiguration(GpsBookmarkConfig.GROUP, GpsBookmarkConfig.BOOKMARKS_KEY, json);
	}

	private void notifyListeners()
	{
		for (Runnable listener : listeners)
		{
			try
			{
				listener.run();
			}
			catch (RuntimeException e)
			{
				log.warn("GPS bookmark change listener threw", e);
			}
		}
	}
}
