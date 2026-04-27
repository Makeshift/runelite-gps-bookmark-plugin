package com.gpsbookmark;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "GPS Bookmarks",
	description = "Bookmark world locations and navigate to them via the Shortest Path plugin",
	tags = {"gps", "bookmark", "navigation", "shortest", "path", "location"}
)
public class GpsBookmarkPlugin extends Plugin
{
	private static final String SHORTEST_PATH_NAMESPACE = "shortestpath";
	private static final String SHORTEST_PATH_PATH = "path";
	private static final String SHORTEST_PATH_CLEAR = "clear";

	private static final Type BOOKMARK_LIST_TYPE = new TypeToken<List<GpsBookmark>>()
	{
	}.getType();

	private static final Type FOLDER_LIST_TYPE = new TypeToken<List<GpsBookmarkFolder>>()
	{
	}.getType();

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ConfigManager configManager;

	@Inject
	private EventBus eventBus;

	@Inject
	private Gson gson;

	private GpsBookmarkPanel panel;
	private NavigationButton navButton;

	private final List<GpsBookmark> bookmarks = new ArrayList<>();
	private final List<GpsBookmarkFolder> folders = new ArrayList<>();

	@Provides
	GpsBookmarkConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GpsBookmarkConfig.class);
	}

	@Override
	protected void startUp()
	{
		loadFolders();
		loadBookmarks();

		panel = new GpsBookmarkPanel(this);
		panel.refresh();

		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");

		navButton = NavigationButton.builder()
			.tooltip("GPS Bookmarks")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		panel = null;
		navButton = null;
		bookmarks.clear();
		folders.clear();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// Refresh the panel when the player logs in so that "use current location"
		// in the add dialog will see an up-to-date player position.
		if (panel != null)
		{
			SwingUtilities.invokeLater(panel::refresh);
		}
	}

	@Subscribe
	public void onProfileChanged(ProfileChanged event)
	{
		// RuneLite stores config per active profile, but plugins are not
		// restarted when the user switches profiles.  Reload the folders and
		// bookmarks from the now-active profile's configuration so the
		// in-memory lists (and any subsequent saves) reflect the chosen
		// profile rather than the one that was active at startup.
		loadFolders();
		loadBookmarks();
		if (panel != null)
		{
			SwingUtilities.invokeLater(panel::refresh);
		}
	}

	// --- Bookmark persistence ---------------------------------------------

	private void loadBookmarks()
	{
		bookmarks.clear();
		final String json = configManager.getConfiguration(GpsBookmarkConfig.GROUP, GpsBookmarkConfig.KEY_BOOKMARKS);
		if (json == null || json.isEmpty())
		{
			return;
		}
		try
		{
			final List<GpsBookmark> loaded = gson.fromJson(json, BOOKMARK_LIST_TYPE);
			if (loaded != null)
			{
				bookmarks.addAll(loaded);
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to load GPS bookmarks", e);
		}
	}

	private void saveBookmarks()
	{
		final String json = gson.toJson(bookmarks, BOOKMARK_LIST_TYPE);
		configManager.setConfiguration(GpsBookmarkConfig.GROUP, GpsBookmarkConfig.KEY_BOOKMARKS, json);
	}

	private void loadFolders()
	{
		folders.clear();
		final String json = configManager.getConfiguration(GpsBookmarkConfig.GROUP, GpsBookmarkConfig.KEY_FOLDERS);
		if (json == null || json.isEmpty())
		{
			return;
		}
		try
		{
			final List<GpsBookmarkFolder> loaded = gson.fromJson(json, FOLDER_LIST_TYPE);
			if (loaded != null)
			{
				folders.addAll(loaded);
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to load GPS bookmark folders", e);
		}
	}

	private void saveFolders()
	{
		final String json = gson.toJson(folders, FOLDER_LIST_TYPE);
		configManager.setConfiguration(GpsBookmarkConfig.GROUP, GpsBookmarkConfig.KEY_FOLDERS, json);
	}

	public List<GpsBookmark> getBookmarks()
	{
		return Collections.unmodifiableList(bookmarks);
	}

	public List<GpsBookmarkFolder> getFolders()
	{
		return Collections.unmodifiableList(folders);
	}

	public GpsBookmarkFolder getFolder(String folderId)
	{
		if (folderId == null)
		{
			return null;
		}
		for (GpsBookmarkFolder folder : folders)
		{
			if (folderId.equals(folder.getId()))
			{
				return folder;
			}
		}
		return null;
	}

	private void refreshPanel()
	{
		if (panel != null)
		{
			panel.refresh();
		}
	}

	// --- Unique name helpers ---------------------------------------------

	/**
	 * Returns a name that is unique among bookmarks. If {@code desired} is
	 * already unique it is returned unchanged, otherwise a numeric suffix
	 * (e.g. " (2)") is appended/incremented until it is.
	 *
	 * @param desired   the requested name
	 * @param excludeId id of a bookmark to ignore when checking for collisions
	 *                  (e.g. when renaming an existing bookmark)
	 */
	public String uniqueBookmarkName(String desired, String excludeId)
	{
		return uniqueName(desired, name ->
		{
			for (GpsBookmark b : bookmarks)
			{
				if (excludeId != null && excludeId.equals(b.getId()))
				{
					continue;
				}
				if (name.equalsIgnoreCase(b.getName()))
				{
					return false;
				}
			}
			return true;
		});
	}

	/**
	 * Returns a name that is unique among folders. See
	 * {@link #uniqueBookmarkName(String, String)} for behaviour.
	 */
	public String uniqueFolderName(String desired, String excludeId)
	{
		return uniqueName(desired, name ->
		{
			for (GpsBookmarkFolder f : folders)
			{
				if (excludeId != null && excludeId.equals(f.getId()))
				{
					continue;
				}
				if (name.equalsIgnoreCase(f.getName()))
				{
					return false;
				}
			}
			return true;
		});
	}

	private static String uniqueName(String desired, java.util.function.Predicate<String> isAvailable)
	{
		final String base = desired == null ? "" : desired.trim();
		if (isAvailable.test(base))
		{
			return base;
		}

		// If the name already ends with " (N)", strip it so the counter increments
		// monotonically rather than producing names like "Foo (2) (2)".
		final java.util.regex.Matcher matcher = java.util.regex.Pattern
			.compile("^(.*) \\((\\d+)\\)$")
			.matcher(base);
		final String stem;
		int counter;
		if (matcher.matches())
		{
			stem = matcher.group(1);
			counter = Integer.parseInt(matcher.group(2)) + 1;
		}
		else
		{
			stem = base;
			counter = 2;
		}

		while (true)
		{
			final String candidate = stem + " (" + counter + ")";
			if (isAvailable.test(candidate))
			{
				return candidate;
			}
			counter++;
		}
	}

	// --- Bookmark CRUD ----------------------------------------------------

	public void addBookmark(GpsBookmark bookmark)
	{
		bookmark.setName(uniqueBookmarkName(bookmark.getName(), bookmark.getId()));
		// Drop dangling folder references so the bookmark stays visible.
		if (bookmark.getFolderId() != null && getFolder(bookmark.getFolderId()) == null)
		{
			bookmark.setFolderId(null);
		}
		bookmarks.add(bookmark);
		saveBookmarks();
		refreshPanel();
	}

	public void updateBookmark(GpsBookmark bookmark)
	{
		for (int i = 0; i < bookmarks.size(); i++)
		{
			if (bookmarks.get(i).getId().equals(bookmark.getId()))
			{
				bookmark.setName(uniqueBookmarkName(bookmark.getName(), bookmark.getId()));
				if (bookmark.getFolderId() != null && getFolder(bookmark.getFolderId()) == null)
				{
					bookmark.setFolderId(null);
				}
				bookmarks.set(i, bookmark);
				saveBookmarks();
				refreshPanel();
				return;
			}
		}
	}

	public void deleteBookmark(GpsBookmark bookmark)
	{
		if (bookmarks.removeIf(b -> b.getId().equals(bookmark.getId())))
		{
			saveBookmarks();
			refreshPanel();
		}
	}

	/**
	 * Creates a copy of {@code bookmark} (with a fresh id and a unique name)
	 * and inserts it directly after the original in the list, preserving the
	 * source bookmark's folder.
	 */
	public void duplicateBookmark(GpsBookmark bookmark)
	{
		final int index = indexOfBookmark(bookmark.getId());
		if (index < 0)
		{
			return;
		}
		final GpsBookmark source = bookmarks.get(index);
		final GpsBookmark copy = new GpsBookmark(
			source.getName(),
			source.getNotes(),
			source.getX(),
			source.getY(),
			source.getPlane(),
			source.getFolderId());
		copy.setName(uniqueBookmarkName(source.getName(), copy.getId()));
		bookmarks.add(index + 1, copy);
		saveBookmarks();
		refreshPanel();
	}

	private int indexOfBookmark(String id)
	{
		for (int i = 0; i < bookmarks.size(); i++)
		{
			if (bookmarks.get(i).getId().equals(id))
			{
				return i;
			}
		}
		return -1;
	}

	// --- Folder CRUD ------------------------------------------------------

	/**
	 * Creates and persists a new folder with a unique name based on
	 * {@code desiredName}. The actual stored name is returned.
	 */
	public GpsBookmarkFolder addFolder(String desiredName)
	{
		final GpsBookmarkFolder folder = new GpsBookmarkFolder(uniqueFolderName(desiredName, null));
		folders.add(folder);
		saveFolders();
		refreshPanel();
		return folder;
	}

	public void renameFolder(GpsBookmarkFolder folder, String newName)
	{
		final GpsBookmarkFolder existing = getFolder(folder.getId());
		if (existing == null)
		{
			return;
		}
		existing.setName(uniqueFolderName(newName, existing.getId()));
		saveFolders();
		refreshPanel();
	}

	/**
	 * Deletes a folder. If {@code deleteContents} is {@code true} all
	 * bookmarks belonging to the folder are deleted as well, otherwise they
	 * are moved to the top level.
	 */
	public void deleteFolder(GpsBookmarkFolder folder, boolean deleteContents)
	{
		if (getFolder(folder.getId()) == null)
		{
			return;
		}
		boolean bookmarksChanged = false;
		if (deleteContents)
		{
			bookmarksChanged = bookmarks.removeIf(b -> folder.getId().equals(b.getFolderId()));
		}
		else
		{
			for (GpsBookmark b : bookmarks)
			{
				if (folder.getId().equals(b.getFolderId()))
				{
					b.setFolderId(null);
					bookmarksChanged = true;
				}
			}
		}
		folders.removeIf(f -> f.getId().equals(folder.getId()));
		saveFolders();
		if (bookmarksChanged)
		{
			saveBookmarks();
		}
		refreshPanel();
	}

	public void setFolderCollapsed(GpsBookmarkFolder folder, boolean collapsed)
	{
		final GpsBookmarkFolder existing = getFolder(folder.getId());
		if (existing == null || existing.isCollapsed() == collapsed)
		{
			return;
		}
		existing.setCollapsed(collapsed);
		saveFolders();
		refreshPanel();
	}

	/**
	 * Moves the supplied bookmarks into the given folder (or to the top level
	 * if {@code folderId} is {@code null}). Bookmarks that don't exist or are
	 * already in the destination folder are skipped.
	 */
	public void moveBookmarksToFolder(List<GpsBookmark> toMove, String folderId)
	{
		if (folderId != null && getFolder(folderId) == null)
		{
			return;
		}
		boolean changed = false;
		for (GpsBookmark requested : toMove)
		{
			for (GpsBookmark stored : bookmarks)
			{
				if (stored.getId().equals(requested.getId())
					&& !java.util.Objects.equals(stored.getFolderId(), folderId))
				{
					stored.setFolderId(folderId);
					changed = true;
					break;
				}
			}
		}
		if (changed)
		{
			saveBookmarks();
			refreshPanel();
		}
	}

	// --- Player location --------------------------------------------------

	/**
	 * Fetches the player's current world location on the client thread and
	 * delivers the result (possibly {@code null} when not logged in) to
	 * {@code callback} on the Swing EDT.  Safe to call from any thread.
	 */
	public void getPlayerLocationAsync(Consumer<WorldPoint> callback)
	{
		clientThread.invokeLater(() ->
		{
			final Player player = client.getLocalPlayer();
			final WorldPoint location = player == null ? null : player.getWorldLocation();
			SwingUtilities.invokeLater(() -> callback.accept(location));
		});
	}

	// --- Shortest Path integration ----------------------------------------

	/**
	 * Requests the Shortest Path plugin to draw a path to the given bookmark.
	 * The starting point defaults to the player's current location (handled by
	 * Shortest Path when {@code start} is omitted).
	 *
	 * <p>The event is posted on the client thread because {@code EventBus.post}
	 * is synchronous: the subscriber (Shortest Path) reads the player's world
	 * location, which asserts it is called on the client thread.</p>
	 */
	public void navigateTo(GpsBookmark bookmark)
	{
		final Map<String, Object> data = new HashMap<>();
		data.put("target", bookmark.toWorldPoint());
		clientThread.invokeLater(() ->
			eventBus.post(new PluginMessage(SHORTEST_PATH_NAMESPACE, SHORTEST_PATH_PATH, data)));
	}

	/**
	 * Requests the Shortest Path plugin to clear any displayed path.
	 * Posted on the client thread; see {@link #navigateTo(GpsBookmark)}.
	 */
	public void clearPath()
	{
		clientThread.invokeLater(() ->
			eventBus.post(new PluginMessage(SHORTEST_PATH_NAMESPACE, SHORTEST_PATH_CLEAR)));
	}
}
