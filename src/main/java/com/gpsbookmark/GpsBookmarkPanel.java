package com.gpsbookmark;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * Sidebar panel that lists all GPS bookmarks and exposes an "Add" button.
 */
class GpsBookmarkPanel extends PluginPanel
{
	private final GpsBookmarkPlugin plugin;
	private final JPanel listPanel = new JPanel();
	private final JLabel emptyLabel = new JLabel("No bookmarks yet. Click 'Add' to create one.", SwingConstants.CENTER);

	GpsBookmarkPanel(GpsBookmarkPlugin plugin)
	{
		this.plugin = plugin;
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		setLayout(new BorderLayout(0, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Header with title + add button
		final JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JLabel title = new JLabel("GPS Bookmarks");
		title.setForeground(java.awt.Color.WHITE);
		header.add(title, BorderLayout.WEST);

		final JButton addButton = new JButton("Add");
		addButton.setToolTipText("Add a new bookmark for a world location");
		addButton.addActionListener(e -> openAddDialog());
		header.add(addButton, BorderLayout.EAST);

		add(header, BorderLayout.NORTH);

		// Scrollable list of bookmarks
		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		listPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		emptyLabel.setForeground(java.awt.Color.LIGHT_GRAY);
		emptyLabel.setAlignmentX(CENTER_ALIGNMENT);

		final JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrapper.add(listPanel, BorderLayout.NORTH);
		add(wrapper, BorderLayout.CENTER);
	}

	void refresh()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::refresh);
			return;
		}

		listPanel.removeAll();

		if (plugin.getBookmarks().isEmpty())
		{
			listPanel.add(emptyLabel);
		}
		else
		{
			for (GpsBookmark bookmark : plugin.getBookmarks())
			{
				listPanel.add(createRow(bookmark));
				listPanel.add(Box.createVerticalStrut(4));
			}
		}

		listPanel.revalidate();
		listPanel.repaint();
	}

	private JPanel createRow(GpsBookmark bookmark)
	{
		final JPanel row = new JPanel(new BorderLayout(4, 4));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));

		final String tooltipText = buildTooltip(bookmark);

		final JLabel name = new JLabel(bookmark.getName());
		name.setForeground(java.awt.Color.WHITE);
		name.setToolTipText(tooltipText);
		row.add(name, BorderLayout.NORTH);
		row.setToolTipText(tooltipText);

		final JPanel buttons = new JPanel(new GridLayout(1, 3, 4, 0));
		buttons.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Compact icon buttons (Unicode glyphs) — full descriptions live in the tooltips.
		final JButton navigate = createIconButton("\u27A4", "Navigate to this bookmark via the Shortest Path plugin");
		navigate.addActionListener(e -> plugin.navigateTo(bookmark));
		buttons.add(navigate);

		final JButton edit = createIconButton("\u270E", "Edit this bookmark");
		edit.addActionListener(e -> openEditDialog(bookmark));
		buttons.add(edit);

		final JButton delete = createIconButton("\u2715", "Delete this bookmark");
		delete.addActionListener(e ->
		{
			final int result = javax.swing.JOptionPane.showConfirmDialog(
				this,
				"Delete bookmark '" + bookmark.getName() + "'?",
				"Delete bookmark",
				javax.swing.JOptionPane.OK_CANCEL_OPTION,
				javax.swing.JOptionPane.WARNING_MESSAGE);
			if (result == javax.swing.JOptionPane.OK_OPTION)
			{
				plugin.deleteBookmark(bookmark);
			}
		});
		buttons.add(delete);

		row.add(buttons, BorderLayout.CENTER);
		return row;
	}

	private static JButton createIconButton(String glyph, String tooltip)
	{
		final JButton button = new JButton(glyph);
		button.setToolTipText(tooltip);
		button.setMargin(new java.awt.Insets(2, 4, 2, 4));
		button.setFont(button.getFont().deriveFont(java.awt.Font.PLAIN, 14f));
		button.setFocusPainted(false);
		return button;
	}

	private static String buildTooltip(GpsBookmark bookmark)
	{
		final String notes = bookmark.getNotes() == null || bookmark.getNotes().isEmpty()
			? "(no notes)"
			: bookmark.getNotes().replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
		return "<html><b>" + escapeHtml(bookmark.getName()) + "</b><br>"
			+ "X: " + bookmark.getX() + ", Y: " + bookmark.getY() + ", Plane: " + bookmark.getPlane() + "<br>"
			+ notes + "</html>";
	}

	private static String escapeHtml(String s)
	{
		if (s == null)
		{
			return "";
		}
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private void openAddDialog()
	{
		BookmarkDialog.show(this, plugin, null);
	}

	private void openEditDialog(GpsBookmark bookmark)
	{
		BookmarkDialog.show(this, plugin, bookmark);
	}
}
