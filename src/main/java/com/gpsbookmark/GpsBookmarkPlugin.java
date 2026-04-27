package com.gpsbookmark;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "GPS Bookmarks",
	description = "Save and navigate to your favourite world locations. Integrates with the Shortest Path plugin.",
	tags = {"gps", "bookmark", "navigation", "shortestpath", "location"}
)
public class GpsBookmarkPlugin extends Plugin implements GpsBookmarkPanel.RowActions
{
	// Cross-plugin namespace/keys defined by the Shortest Path plugin.
	// See: https://github.com/Skretzo/shortest-path/wiki/Cross-plugin-communication
	private static final String SHORTEST_PATH_NAMESPACE = "shortestpath";
	private static final String SHORTEST_PATH_PATH_NAME = "path";
	private static final String SHORTEST_PATH_TARGET_KEY = "target";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private EventBus eventBus;

	@Inject
	private GpsBookmarkManager manager;

	@Inject
	private GpsBookmarkPanel panel;

	private NavigationButton navButton;

	@Provides
	GpsBookmarkConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GpsBookmarkConfig.class);
	}

	@Override
	protected void startUp()
	{
		panel.setActions(this);
		SwingUtilities.invokeLater(panel::rebuild);

		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/icon.png");

		navButton = NavigationButton.builder()
			.tooltip("GPS Bookmarks")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);
		log.debug("GPS Bookmarks started");
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		navButton = null;
		log.debug("GPS Bookmarks stopped");
	}

	// --- RowActions ---------------------------------------------------------

	@Override
	public void add()
	{
		// getLocalPlayer() must be read on the client thread.
		clientThread.invoke(() ->
		{
			final Player local = client.getLocalPlayer();
			final WorldPoint prefill = local != null ? local.getWorldLocation() : null;
			SwingUtilities.invokeLater(() -> BookmarkDialog.showAdd(panel, prefill, manager::addBookmark));
		});
	}

	@Override
	public void edit(GpsBookmark bookmark)
	{
		SwingUtilities.invokeLater(() -> BookmarkDialog.showEdit(panel, bookmark, manager::updateBookmark));
	}

	@Override
	public void delete(GpsBookmark bookmark)
	{
		SwingUtilities.invokeLater(() ->
		{
			final int choice = JOptionPane.showConfirmDialog(panel,
				"Delete bookmark \"" + bookmark.getName() + "\"?",
				"Delete bookmark",
				JOptionPane.YES_NO_OPTION,
				JOptionPane.WARNING_MESSAGE);
			if (choice == JOptionPane.YES_OPTION)
			{
				manager.removeBookmark(bookmark.getId());
			}
		});
	}

	@Override
	public void navigate(GpsBookmark bookmark)
	{
		final WorldPoint target = bookmark.toWorldPoint();
		final Map<String, Object> data = new HashMap<>();
		data.put(SHORTEST_PATH_TARGET_KEY, target);

		// If the Shortest Path plugin is not installed/enabled, this message is simply ignored.
		eventBus.post(new PluginMessage(SHORTEST_PATH_NAMESPACE, SHORTEST_PATH_PATH_NAME, data));

		clientThread.invoke(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
			"GPS Bookmarks: navigating to " + bookmark.getName()
				+ " (requires the Shortest Path plugin).", null));
	}
}
