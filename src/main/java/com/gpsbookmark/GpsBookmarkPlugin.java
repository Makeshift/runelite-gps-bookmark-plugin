package com.gpsbookmark;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
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
	private static final String SHORTEST_PATH_TRANSPORTS = "transports";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ChatMessageManager chatMessageManager;

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
	private PoiCatalog poiCatalog;

	@Provides
	GpsBookmarkConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GpsBookmarkConfig.class);
	}

	@Override
	protected void startUp()
	{
		loadData();

		poiCatalog = new PoiCatalog(gson);
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
		// restarted when the user switches profiles.  Reload the unified
		// data document from the now-active profile's configuration so the
		// in-memory lists (and any subsequent saves) reflect the chosen
		// profile rather than the one that was active at startup.
		loadData();
		if (panel != null)
		{
			SwingUtilities.invokeLater(panel::refresh);
		}
	}

	/**
	 * Re-enables the "Find closest" Go button as soon as Shortest Path
	 * publishes its post-pathfinding {@code transports} message, which is
	 * the only outgoing PluginMessage upstream emits and fires when
	 * pathfinding has completed (gated by upstream's {@code postTransports}
	 * config). Without this hook the panel falls back to a fixed timeout.
	 */
	@Subscribe
	public void onPluginMessage(PluginMessage event)
	{
		if (panel != null
			&& SHORTEST_PATH_NAMESPACE.equals(event.getNamespace())
			&& SHORTEST_PATH_TRANSPORTS.equals(event.getName()))
		{
			SwingUtilities.invokeLater(panel::onShortestPathPathReady);
		}
	}

	// --- Persistence ------------------------------------------------------

	/**
	 * Replaces the in-memory folder/bookmark lists with the contents of the
	 * unified {@link GpsBookmarkData} document stored in the active
	 * profile's configuration.
	 */
	private void loadData()
	{
		folders.clear();
		bookmarks.clear();

		final GpsBookmarkData data = readData();
		if (data.getFolders() != null)
		{
			folders.addAll(data.getFolders());
		}
		if (data.getBookmarks() != null)
		{
			bookmarks.addAll(data.getBookmarks());
		}
	}

	private GpsBookmarkData readData()
	{
		final String json = configManager.getConfiguration(GpsBookmarkConfig.GROUP, GpsBookmarkConfig.KEY_DATA);
		if (json == null || json.isEmpty())
		{
			return GpsBookmarkData.empty();
		}
		try
		{
			final GpsBookmarkData data = gson.fromJson(json, GpsBookmarkData.class);
			if (data != null)
			{
				if (data.getVersion() <= 0)
				{
					data.setVersion(GpsBookmarkData.CURRENT_VERSION);
				}
				if (data.getFolders() == null)
				{
					data.setFolders(new ArrayList<>());
				}
				if (data.getBookmarks() == null)
				{
					data.setBookmarks(new ArrayList<>());
				}
				return data;
			}
		}
		catch (JsonSyntaxException e)
		{
			log.warn("Failed to parse GPS bookmark data; starting empty", e);
		}
		return GpsBookmarkData.empty();
	}

	/**
	 * Persists the current in-memory folders + bookmarks as a single
	 * {@link GpsBookmarkData} document. All CRUD operations funnel through
	 * here so the two lists are always written atomically.
	 */
	private void saveData()
	{
		final GpsBookmarkData data = new GpsBookmarkData(GpsBookmarkData.CURRENT_VERSION,
			new ArrayList<>(folders), new ArrayList<>(bookmarks));
		final String json = gson.toJson(data, GpsBookmarkData.class);
		configManager.setConfiguration(GpsBookmarkConfig.GROUP, GpsBookmarkConfig.KEY_DATA, json);
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
		log.debug("addBookmark name='{}' folderId={} location={}",
			bookmark.getName(), bookmark.getFolderId(), bookmark.toWorldPoint());
		saveData();
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
				log.debug("updateBookmark id={} name='{}' folderId={}",
					bookmark.getId(), bookmark.getName(), bookmark.getFolderId());
				saveData();
				refreshPanel();
				return;
			}
		}
	}

	public void deleteBookmark(GpsBookmark bookmark)
	{
		if (bookmarks.removeIf(b -> b.getId().equals(bookmark.getId())))
		{
			log.debug("deleteBookmark id={} name='{}'", bookmark.getId(), bookmark.getName());
			saveData();
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
		saveData();
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
		log.debug("addFolder name='{}' id={}", folder.getName(), folder.getId());
		saveData();
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
		final String oldName = existing.getName();
		existing.setName(uniqueFolderName(newName, existing.getId()));
		log.debug("renameFolder id={} '{}' -> '{}'", existing.getId(), oldName, existing.getName());
		saveData();
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
		if (deleteContents)
		{
			bookmarks.removeIf(b -> folder.getId().equals(b.getFolderId()));
		}
		else
		{
			for (GpsBookmark b : bookmarks)
			{
				if (folder.getId().equals(b.getFolderId()))
				{
					b.setFolderId(null);
				}
			}
		}
		folders.removeIf(f -> f.getId().equals(folder.getId()));
		log.debug("deleteFolder id={} name='{}' deleteContents={}",
			folder.getId(), folder.getName(), deleteContents);
		saveData();
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
		saveData();
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
			saveData();
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
		final WorldPoint target = bookmark.toWorldPoint();
		log.debug("navigateTo bookmark='{}' target={}", bookmark.getName(), target);
		sendChatMessage(new ChatMessageBuilder()
			.append(ChatColorType.NORMAL)
			.append("Navigating to bookmark ")
			.append(ChatColorType.HIGHLIGHT)
			.append(bookmark.getName())
			.append(ChatColorType.NORMAL)
			.append(".")
			.build());
		final Map<String, Object> data = new HashMap<>();
		data.put("target", target);
		clientThread.invokeLater(() ->
			eventBus.post(new PluginMessage(SHORTEST_PATH_NAMESPACE, SHORTEST_PATH_PATH, data)));
	}

	/**
	 * Asks the Shortest Path plugin to navigate to the closest reachable
	 * point in a built-in POI category (e.g. nearest bank). The path field
	 * accepts a {@code Set<WorldPoint>} as its target, which triggers a
	 * multi-target BFS in upstream's {@code Pathfinder} that picks whichever
	 * destination is reachable in the fewest tiles (transports included).
	 *
	 * @return {@code true} if the request was dispatched, {@code false} if
	 *         there is no embedded POI data for the requested category.
	 */
	public boolean navigateToClosest(String poiCategory)
	{
		if (poiCatalog == null)
		{
			log.warn("navigateToClosest('{}') ignored: POI catalog not initialised", poiCategory);
			return false;
		}
		final List<WorldPoint> points = poiCatalog.getPoints(poiCategory);
		if (points.isEmpty())
		{
			log.warn("No POI data available for category '{}'", poiCategory);
			sendChatMessage(new ChatMessageBuilder()
				.append(ChatColorType.NORMAL)
				.append("No data available for category ")
				.append(ChatColorType.HIGHLIGHT)
				.append(poiCategory)
				.append(ChatColorType.NORMAL)
				.append(".")
				.build());
			return false;
		}

		log.debug("navigateToClosest category='{}' candidateCount={}", poiCategory, points.size());
		sendChatMessage(new ChatMessageBuilder()
			.append(ChatColorType.NORMAL)
			.append("Finding closest ")
			.append(ChatColorType.HIGHLIGHT)
			.append(poiCategory)
			.append(ChatColorType.NORMAL)
			.append(" (")
			.append(Integer.toString(points.size()))
			.append(" candidates)...")
			.build());

		final Set<WorldPoint> targets = new HashSet<>(points);
		final Map<String, Object> data = new HashMap<>();
		data.put("target", targets);
		clientThread.invokeLater(() ->
			eventBus.post(new PluginMessage(SHORTEST_PATH_NAMESPACE, SHORTEST_PATH_PATH, data)));
		return true;
	}

	public PoiCatalog getPoiCatalog()
	{
		return poiCatalog;
	}

	/**
	 * Requests the Shortest Path plugin to clear any displayed path.
	 * Posted on the client thread; see {@link #navigateTo(GpsBookmark)}.
	 */
	public void clearPath()
	{
		log.debug("clearPath");
		clientThread.invokeLater(() ->
			eventBus.post(new PluginMessage(SHORTEST_PATH_NAMESPACE, SHORTEST_PATH_CLEAR)));
	}

	/**
	 * Sends a coloured game-message chat line prefixed with the plugin
	 * name so users can see what the GPS Bookmarks plugin is doing.
	 * Silently skipped when the player is not logged in (the chat box
	 * isn't drawn on the login screen).
	 */
	private void sendChatMessage(String message)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		final String prefixed = new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append("[GPS] ")
			.append(message)
			.build();
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(prefixed)
			.build());
	}
}
