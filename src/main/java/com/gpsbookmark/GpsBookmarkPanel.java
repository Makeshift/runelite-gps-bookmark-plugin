package com.gpsbookmark;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * Sidebar panel that lists all GPS bookmarks (optionally grouped into
 * collapsible folders) and exposes buttons to add bookmarks/folders and to
 * clear the current Shortest Path.
 */
class GpsBookmarkPanel extends PluginPanel
{
	/**
	 * Hard cap on how long the "Find closest" button stays disabled after a
	 * Go click, in case Shortest Path never publishes its post-pathfinding
	 * {@code transports} PluginMessage (it's gated by upstream's
	 * {@code postTransports} config option, off by default).
	 */
	private static final int FIND_CLOSEST_TIMEOUT_MS = 8000;

	private final GpsBookmarkPlugin plugin;
	private final JPanel listPanel = new JPanel();
	private final JLabel emptyLabel = new JLabel("No bookmarks yet. Click '+' to create one.", SwingConstants.CENTER);

	// "Find closest" widgets are referenced from setFindClosestBusy(...).
	private JComboBox<String> findClosestDropdown;
	private JButton findClosestGo;
	private String findClosestGoLabel;
	private Timer findClosestResetTimer;

	GpsBookmarkPanel(GpsBookmarkPlugin plugin)
	{
		// Opt out of PluginPanel's default behaviour of wrapping the whole
		// panel in a single scroll pane: we want the bookmark list to scroll
		// independently while the header and "find closest" footer stay
		// pinned to the top/bottom of the sidebar.
		super(false);
		this.plugin = plugin;
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		setLayout(new BorderLayout(0, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Header with title + add button
		final JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JLabel title = new JLabel("GPS Bookmarks");
		title.setForeground(Color.WHITE);
		header.add(title, BorderLayout.WEST);

		final JPanel headerButtons = new JPanel(new GridLayout(1, 3, 4, 0));
		headerButtons.setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JButton addButton = createIconButton("\u002B", "Add a new bookmark for a world location");
		addButton.addActionListener(e -> openAddDialog());
		headerButtons.add(addButton);

		final JButton addFolderButton = createIconButton("\uD83D\uDCC1", "Create a new folder");
		addFolderButton.addActionListener(e -> openAddFolderDialog());
		headerButtons.add(addFolderButton);

		final JButton clearButton = createIconButton("\u29B8", "Clear the current Shortest Path target");
		clearButton.addActionListener(e -> plugin.clearPath());
		headerButtons.add(clearButton);

		header.add(headerButtons, BorderLayout.EAST);

		add(header, BorderLayout.NORTH);

		// Scrollable list of bookmarks
		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		listPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		emptyLabel.setForeground(Color.LIGHT_GRAY);
		emptyLabel.setAlignmentX(CENTER_ALIGNMENT);

		final JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrapper.add(listPanel, BorderLayout.NORTH);

		final JScrollPane scrollPane = new JScrollPane(wrapper);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(scrollPane, BorderLayout.CENTER);

		add(createFindClosestPanel(), BorderLayout.SOUTH);
	}

	/**
	 * Builds the bottom "Find closest" row: a dropdown of pre-defined POI
	 * categories (currently just Bank) plus a Go button that hands the
	 * category's world points to Shortest Path as a multi-target query.
	 */
	private JPanel createFindClosestPanel()
	{
		final JPanel container = new JPanel(new BorderLayout(0, 4));
		container.setBackground(ColorScheme.DARK_GRAY_COLOR);
		container.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, ColorScheme.DARKER_GRAY_HOVER_COLOR),
			BorderFactory.createEmptyBorder(6, 0, 0, 0)));

		final JLabel label = new JLabel("Find closest:");
		label.setForeground(Color.WHITE);
		label.setToolTipText("Navigate to the closest reachable point in the selected category via Shortest Path");
		container.add(label, BorderLayout.NORTH);

		final JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);

		final List<String> categories = plugin.getPoiCatalog() == null
			? java.util.Collections.singletonList(PoiCatalog.BANKS)
			: plugin.getPoiCatalog().categories();
		final JComboBox<String> dropdown = new JComboBox<>(categories.toArray(new String[0]));
		dropdown.setToolTipText("Pre-defined points of interest to navigate to");
		row.add(dropdown, BorderLayout.CENTER);

		final JButton go = new JButton("Go");
		go.setToolTipText("Navigate to the closest reachable item of the selected category");
		go.addActionListener(e ->
		{
			final Object selected = dropdown.getSelectedItem();
			if (selected == null)
			{
				return;
			}
			// Disable the controls and surface that pathfinding is in
			// progress.  Shortest Path runs the BFS on a background
			// executor, so for large target sets there can be a
			// noticeable delay before anything visibly happens; the
			// disabled state both prevents double-submission and tells
			// the user that something is happening.
			final boolean dispatched = plugin.navigateToClosest(selected.toString());
			if (dispatched)
			{
				setFindClosestBusy(true);
			}
		});
		row.add(go, BorderLayout.EAST);

		findClosestDropdown = dropdown;
		findClosestGo = go;
		findClosestGoLabel = go.getText();

		container.add(row, BorderLayout.CENTER);
		return container;
	}

	/**
	 * Toggles the "Find closest" Go button between idle and "calculating"
	 * states. When busy, the dropdown and button are disabled and the
	 * button text becomes "Calculating…"; a {@link Timer} re-enables them
	 * after {@link #FIND_CLOSEST_TIMEOUT_MS} as a fallback in case
	 * Shortest Path never publishes its post-pathfinding message.
	 */
	private void setFindClosestBusy(boolean busy)
	{
		if (findClosestGo == null)
		{
			return;
		}
		if (findClosestResetTimer != null)
		{
			findClosestResetTimer.stop();
			findClosestResetTimer = null;
		}
		if (busy)
		{
			findClosestGo.setEnabled(false);
			findClosestGo.setText("Calculating\u2026");
			findClosestDropdown.setEnabled(false);
			findClosestResetTimer = new Timer(FIND_CLOSEST_TIMEOUT_MS, e -> setFindClosestBusy(false));
			findClosestResetTimer.setRepeats(false);
			findClosestResetTimer.start();
		}
		else
		{
			findClosestGo.setEnabled(true);
			findClosestGo.setText(findClosestGoLabel);
			findClosestDropdown.setEnabled(true);
		}
	}

	/**
	 * Called by {@link GpsBookmarkPlugin} when Shortest Path publishes its
	 * post-pathfinding {@code transports} PluginMessage, signalling that
	 * pathfinding finished and navigation is now visible to the user. Safe
	 * no-op if the button is not currently in the busy state.
	 */
	void onShortestPathPathReady()
	{
		if (findClosestGo != null && !findClosestGo.isEnabled())
		{
			setFindClosestBusy(false);
		}
	}

	void refresh()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::refresh);
			return;
		}

		listPanel.removeAll();

		final List<GpsBookmark> all = plugin.getBookmarks();
		final List<GpsBookmarkFolder> allFolders = plugin.getFolders();

		if (all.isEmpty() && allFolders.isEmpty())
		{
			listPanel.add(emptyLabel);
		}
		else
		{
			// Folders (and their contents) come first, then top-level bookmarks.
			for (GpsBookmarkFolder folder : allFolders)
			{
				listPanel.add(createFolderHeader(folder));
				listPanel.add(Box.createVerticalStrut(4));
				if (!folder.isCollapsed())
				{
					for (GpsBookmark bookmark : all)
					{
						if (folder.getId().equals(bookmark.getFolderId()))
						{
							listPanel.add(createIndentedRow(bookmark));
							listPanel.add(Box.createVerticalStrut(4));
						}
					}
				}
			}

			boolean hasTopLevel = false;
			for (GpsBookmark bookmark : all)
			{
				if (bookmark.getFolderId() == null || plugin.getFolder(bookmark.getFolderId()) == null)
				{
					hasTopLevel = true;
					break;
				}
			}
			if (hasTopLevel && !allFolders.isEmpty())
			{
				listPanel.add(Box.createVerticalStrut(4));
			}
			for (GpsBookmark bookmark : all)
			{
				if (bookmark.getFolderId() == null || plugin.getFolder(bookmark.getFolderId()) == null)
				{
					listPanel.add(createRow(bookmark));
					listPanel.add(Box.createVerticalStrut(4));
				}
			}
		}

		listPanel.revalidate();
		listPanel.repaint();
	}

	private JPanel createFolderHeader(GpsBookmarkFolder folder)
	{
		final JPanel header = new JPanel(new BorderLayout(4, 0));
		header.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
		header.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		final JLabel arrow = new JLabel(folder.isCollapsed() ? "\u25B6" : "\u25BC");
		arrow.setForeground(Color.WHITE);
		header.add(arrow, BorderLayout.WEST);

		final JLabel name = new JLabel(folder.getName());
		name.setForeground(Color.WHITE);
		name.setToolTipText("Folder: " + folder.getName() + " (right-click for options)");
		header.add(name, BorderLayout.CENTER);

		header.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1)
				{
					plugin.setFolderCollapsed(folder, !folder.isCollapsed());
				}
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				maybeShowMenu(e);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				maybeShowMenu(e);
			}

			private void maybeShowMenu(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					buildFolderMenu(folder).show(e.getComponent(), e.getX(), e.getY());
				}
			}
		});

		return header;
	}

	private JPopupMenu buildFolderMenu(GpsBookmarkFolder folder)
	{
		final JPopupMenu menu = new JPopupMenu();

		final javax.swing.JMenuItem add = new javax.swing.JMenuItem("Add bookmark in folder...");
		add.addActionListener(e -> BookmarkDialog.show(this, plugin, null, folder.getId()));
		menu.add(add);

		final javax.swing.JMenuItem addExisting = new javax.swing.JMenuItem("Add locations...");
		addExisting.addActionListener(e -> openAddLocationsDialog(folder));
		menu.add(addExisting);

		menu.addSeparator();

		final javax.swing.JMenuItem rename = new javax.swing.JMenuItem("Rename");
		rename.addActionListener(e -> openRenameFolderDialog(folder));
		menu.add(rename);

		final javax.swing.JMenuItem delete = new javax.swing.JMenuItem("Delete");
		delete.addActionListener(e -> deleteFolderWithPrompt(folder));
		menu.add(delete);

		return menu;
	}

	private void deleteFolderWithPrompt(GpsBookmarkFolder folder)
	{
		boolean hasChildren = false;
		for (GpsBookmark b : plugin.getBookmarks())
		{
			if (folder.getId().equals(b.getFolderId()))
			{
				hasChildren = true;
				break;
			}
		}

		if (!hasChildren)
		{
			final int result = JOptionPane.showConfirmDialog(
				this,
				"Delete folder '" + folder.getName() + "'?",
				"Delete folder",
				JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.WARNING_MESSAGE);
			if (result == JOptionPane.OK_OPTION)
			{
				plugin.deleteFolder(folder, false);
			}
			return;
		}

		final Object[] options = {"Move bookmarks out", "Delete bookmarks", "Cancel"};
		final int choice = JOptionPane.showOptionDialog(
			this,
			"Folder '" + folder.getName() + "' contains bookmarks.\n"
				+ "Do you want to move them to the top level or delete them along with the folder?",
			"Delete folder",
			JOptionPane.YES_NO_CANCEL_OPTION,
			JOptionPane.WARNING_MESSAGE,
			null,
			options,
			options[0]);
		if (choice == 0)
		{
			plugin.deleteFolder(folder, false);
		}
		else if (choice == 1)
		{
			plugin.deleteFolder(folder, true);
		}
	}

	private void openRenameFolderDialog(GpsBookmarkFolder folder)
	{
		final String input = (String) JOptionPane.showInputDialog(
			this,
			"New name:",
			"Rename folder",
			JOptionPane.PLAIN_MESSAGE,
			null,
			null,
			folder.getName());
		if (input == null)
		{
			return;
		}
		final String trimmed = input.trim();
		if (trimmed.isEmpty())
		{
			JOptionPane.showMessageDialog(this, "Folder name cannot be empty.", "Invalid name", JOptionPane.ERROR_MESSAGE);
			return;
		}
		plugin.renameFolder(folder, trimmed);
	}

	private void openAddFolderDialog()
	{
		final String input = JOptionPane.showInputDialog(
			this,
			"Folder name:",
			"New folder",
			JOptionPane.PLAIN_MESSAGE);
		if (input == null)
		{
			return;
		}
		final String trimmed = input.trim();
		if (trimmed.isEmpty())
		{
			JOptionPane.showMessageDialog(this, "Folder name cannot be empty.", "Invalid name", JOptionPane.ERROR_MESSAGE);
			return;
		}
		plugin.addFolder(trimmed);
	}

	private void openAddLocationsDialog(GpsBookmarkFolder folder)
	{
		final List<GpsBookmark> all = plugin.getBookmarks();
		if (all.isEmpty())
		{
			JOptionPane.showMessageDialog(this, "There are no bookmarks to add.", "Add locations", JOptionPane.INFORMATION_MESSAGE);
			return;
		}

		final java.awt.Window owner = SwingUtilities.getWindowAncestor(this);
		final JDialog dialog = new JDialog(owner, "Add locations to '" + folder.getName() + "'", JDialog.ModalityType.APPLICATION_MODAL);
		dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		dialog.setLayout(new BorderLayout(8, 8));

		final JPanel checkboxPanel = new JPanel();
		checkboxPanel.setLayout(new BoxLayout(checkboxPanel, BoxLayout.Y_AXIS));
		checkboxPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		final List<JCheckBox> boxes = new ArrayList<>(all.size());
		final List<GpsBookmark> bookmarksByIndex = new ArrayList<>(all.size());
		for (GpsBookmark bookmark : all)
		{
			final String suffix;
			if (folder.getId().equals(bookmark.getFolderId()))
			{
				suffix = " (already in this folder)";
			}
			else if (bookmark.getFolderId() != null && plugin.getFolder(bookmark.getFolderId()) != null)
			{
				suffix = " (in '" + plugin.getFolder(bookmark.getFolderId()).getName() + "')";
			}
			else
			{
				suffix = "";
			}
			final JCheckBox box = new JCheckBox(bookmark.getName() + suffix);
			box.setSelected(false);
			box.setEnabled(!folder.getId().equals(bookmark.getFolderId()));
			boxes.add(box);
			bookmarksByIndex.add(bookmark);
			checkboxPanel.add(box);
		}

		final JScrollPane scroll = new JScrollPane(checkboxPanel);
		scroll.setPreferredSize(new Dimension(320, Math.min(400, 24 + 28 * Math.min(all.size(), 12))));
		dialog.add(scroll, BorderLayout.CENTER);

		final JPanel buttons = new JPanel();
		buttons.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

		final JButton ok = new JButton("Add to folder");
		ok.addActionListener(e ->
		{
			final List<GpsBookmark> selected = new ArrayList<>();
			for (int i = 0; i < boxes.size(); i++)
			{
				if (boxes.get(i).isSelected())
				{
					selected.add(bookmarksByIndex.get(i));
				}
			}
			plugin.moveBookmarksToFolder(selected, folder.getId());
			dialog.dispose();
		});
		buttons.add(ok);

		final JButton cancel = new JButton("Cancel");
		cancel.addActionListener(e -> dialog.dispose());
		buttons.add(cancel);

		dialog.add(buttons, BorderLayout.SOUTH);
		dialog.getRootPane().setDefaultButton(ok);
		dialog.pack();
		dialog.setLocationRelativeTo(this);
		dialog.setVisible(true);
	}

	private JPanel createIndentedRow(GpsBookmark bookmark)
	{
		final JPanel container = new JPanel(new BorderLayout());
		container.setBackground(ColorScheme.DARK_GRAY_COLOR);
		container.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 0));
		container.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
		container.add(createRow(bookmark), BorderLayout.CENTER);
		return container;
	}

	private JPanel createRow(GpsBookmark bookmark)
	{
		final JPanel row = new JPanel(new GridBagLayout());
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));

		final String tooltipText = buildTooltip(bookmark);

		final GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = 0;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.insets = new Insets(0, 0, 4, 0);

		final JLabel name = new JLabel(bookmark.getName());
		name.setForeground(Color.WHITE);
		name.setToolTipText(tooltipText);
		row.add(name, c);
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
			final int result = JOptionPane.showConfirmDialog(
				this,
				"Delete bookmark '" + bookmark.getName() + "'?",
				"Delete bookmark",
				JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.WARNING_MESSAGE);
			if (result == JOptionPane.OK_OPTION)
			{
				plugin.deleteBookmark(bookmark);
			}
		});
		buttons.add(delete);

		c.gridy = 1;
		c.insets = new Insets(0, 0, 0, 0);
		row.add(buttons, c);

		final MouseAdapter popupListener = new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				maybeShow(e);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				maybeShow(e);
			}

			private void maybeShow(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					buildBookmarkMenu(bookmark).show(e.getComponent(), e.getX(), e.getY());
				}
			}
		};
		row.addMouseListener(popupListener);
		name.addMouseListener(popupListener);
		return row;
	}

	private JPopupMenu buildBookmarkMenu(GpsBookmark bookmark)
	{
		final JPopupMenu menu = new JPopupMenu();

		final javax.swing.JMenuItem edit = new javax.swing.JMenuItem("Edit");
		edit.addActionListener(e -> openEditDialog(bookmark));
		menu.add(edit);

		final javax.swing.JMenuItem duplicate = new javax.swing.JMenuItem("Duplicate");
		duplicate.addActionListener(e -> plugin.duplicateBookmark(bookmark));
		menu.add(duplicate);

		menu.addSeparator();

		final javax.swing.JMenuItem delete = new javax.swing.JMenuItem("Delete");
		delete.addActionListener(e ->
		{
			final int result = JOptionPane.showConfirmDialog(
				this,
				"Delete bookmark '" + bookmark.getName() + "'?",
				"Delete bookmark",
				JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.WARNING_MESSAGE);
			if (result == JOptionPane.OK_OPTION)
			{
				plugin.deleteBookmark(bookmark);
			}
		});
		menu.add(delete);

		return menu;
	}

	private static JButton createIconButton(String glyph, String tooltip)
	{
		final JButton button = new JButton(glyph);
		button.setToolTipText(tooltip);
		button.setMargin(new Insets(2, 4, 2, 4));
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
