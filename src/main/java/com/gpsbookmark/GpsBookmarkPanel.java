package com.gpsbookmark;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import lombok.Setter;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Sidebar panel that lists {@link GpsBookmark}s and provides Add / Edit / Delete / Navigate
 * controls. The actual action implementations are provided by {@link GpsBookmarkPlugin} via
 * {@link #setActions(RowActions)} to keep this panel free of plugin lifecycle wiring.
 */
@Singleton
public class GpsBookmarkPanel extends PluginPanel
{
	private final GpsBookmarkManager manager;

	private final JPanel listPanel;
	private final JLabel emptyLabel;

	@Setter
	private RowActions actions;

	private final Runnable changeListener = this::rebuild;

	@Inject
	GpsBookmarkPanel(GpsBookmarkManager manager)
	{
		this.manager = manager;

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		final JLabel title = new JLabel("GPS Bookmarks");
		title.setFont(FontManager.getRunescapeBoldFont().deriveFont(Font.BOLD, 16f));
		title.setForeground(Color.WHITE);

		final JButton addButton = new JButton("Add");
		addButton.setToolTipText("Add a new bookmark for the current location");
		addButton.addActionListener(e ->
		{
			if (actions != null)
			{
				actions.add();
			}
		});

		final JPanel header = new JPanel(new BorderLayout());
		header.setOpaque(false);
		header.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		header.add(title, BorderLayout.WEST);
		final JPanel addWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		addWrap.setOpaque(false);
		addWrap.add(addButton);
		header.add(addWrap, BorderLayout.EAST);

		listPanel = new JPanel();
		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		listPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		emptyLabel = new JLabel("<html><div style='text-align:center'>"
			+ "No bookmarks yet.<br>Click <b>Add</b> to save your current location.</div></html>");
		emptyLabel.setHorizontalAlignment(JLabel.CENTER);
		emptyLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		emptyLabel.setBorder(BorderFactory.createEmptyBorder(20, 8, 20, 8));
		emptyLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

		final JScrollPane scroll = new JScrollPane(listPanel,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.setPreferredSize(new Dimension(PANEL_WIDTH, 400));

		add(header, BorderLayout.NORTH);
		add(scroll, BorderLayout.CENTER);

		manager.addChangeListener(() -> SwingUtilities.invokeLater(changeListener));
		rebuild();
	}

	/**
	 * Rebuilds the list contents from the {@link GpsBookmarkManager}. Must be called on the EDT.
	 */
	void rebuild()
	{
		assert SwingUtilities.isEventDispatchThread();
		listPanel.removeAll();

		final List<GpsBookmark> bookmarks = manager.getBookmarks();
		if (bookmarks.isEmpty())
		{
			listPanel.add(emptyLabel);
		}
		else if (actions != null)
		{
			for (GpsBookmark bookmark : bookmarks)
			{
				final BookmarkRow row = new BookmarkRow(bookmark, actions);
				row.setAlignmentX(Component.LEFT_ALIGNMENT);
				listPanel.add(row);
			}
		}

		listPanel.revalidate();
		listPanel.repaint();
	}

	/**
	 * Callbacks invoked from rows / the Add button.
	 */
	public interface RowActions
	{
		void add();
		void edit(GpsBookmark bookmark);
		void delete(GpsBookmark bookmark);
		void navigate(GpsBookmark bookmark);
	}
}
