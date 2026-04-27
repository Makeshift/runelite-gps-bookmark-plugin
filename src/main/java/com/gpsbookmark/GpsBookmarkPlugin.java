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

	@Provides
	GpsBookmarkConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GpsBookmarkConfig.class);
	}

	@Override
	protected void startUp()
	{
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
		// restarted when the user switches profiles.  Reload the bookmarks
		// from the now-active profile's configuration so the in-memory list
		// (and any subsequent saves) reflect the chosen profile rather than
		// the one that was active at startup.
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

	public List<GpsBookmark> getBookmarks()
	{
		return Collections.unmodifiableList(bookmarks);
	}

	public void addBookmark(GpsBookmark bookmark)
	{
		bookmarks.add(bookmark);
		saveBookmarks();
		if (panel != null)
		{
			panel.refresh();
		}
	}

	public void updateBookmark(GpsBookmark bookmark)
	{
		for (int i = 0; i < bookmarks.size(); i++)
		{
			if (bookmarks.get(i).getId().equals(bookmark.getId()))
			{
				bookmarks.set(i, bookmark);
				saveBookmarks();
				if (panel != null)
				{
					panel.refresh();
				}
				return;
			}
		}
	}

	public void deleteBookmark(GpsBookmark bookmark)
	{
		if (bookmarks.removeIf(b -> b.getId().equals(bookmark.getId())))
		{
			saveBookmarks();
			if (panel != null)
			{
				panel.refresh();
			}
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
