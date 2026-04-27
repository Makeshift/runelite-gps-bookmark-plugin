package com.gpsbookmark;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * A single row in the {@link GpsBookmarkPanel} representing one bookmark.
 *
 * <p>The bookmark's notes are exposed as a tooltip on the row, so hovering shows them.
 */
final class BookmarkRow extends JPanel
{
	BookmarkRow(GpsBookmark bookmark, GpsBookmarkPanel.RowActions actions)
	{
		setLayout(new BorderLayout(4, 0));
		setBackground(ColorScheme.DARKER_GRAY_COLOR);
		setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
			BorderFactory.createEmptyBorder(6, 6, 6, 6)));

		final JPanel info = new JPanel();
		info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
		info.setOpaque(false);

		final JLabel nameLabel = new JLabel(bookmark.getName());
		nameLabel.setFont(FontManager.getRunescapeBoldFont());
		nameLabel.setForeground(Color.WHITE);

		final JLabel coordsLabel = new JLabel(String.format("(%d, %d, %d)",
			bookmark.getX(), bookmark.getY(), bookmark.getPlane()));
		coordsLabel.setFont(FontManager.getRunescapeSmallFont());
		coordsLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		info.add(nameLabel);
		info.add(coordsLabel);

		final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		buttons.setOpaque(false);
		buttons.add(makeButton("Navigate", "Start Shortest Path navigation to this bookmark",
			e -> actions.navigate(bookmark)));
		buttons.add(makeButton("Edit", "Edit this bookmark", e -> actions.edit(bookmark)));
		buttons.add(makeButton("Delete", "Delete this bookmark", e -> actions.delete(bookmark)));

		add(info, BorderLayout.CENTER);
		add(buttons, BorderLayout.EAST);

		final String notes = bookmark.getNotes();
		if (notes != null && !notes.isEmpty())
		{
			final String tip = "<html>" + escapeHtml(notes).replace("\n", "<br>") + "</html>";
			setToolTipText(tip);
			info.setToolTipText(tip);
			nameLabel.setToolTipText(tip);
			coordsLabel.setToolTipText(tip);
		}

		setMaximumSize(new Dimension(Integer.MAX_VALUE, getPreferredSize().height));
	}

	private static JButton makeButton(String text, String tooltip, java.awt.event.ActionListener listener)
	{
		final JButton button = new JButton(text);
		button.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.PLAIN, 11f));
		button.setToolTipText(tooltip);
		button.setFocusable(false);
		button.setMargin(new java.awt.Insets(2, 6, 2, 6));
		button.addActionListener(listener);
		return button;
	}

	private static String escapeHtml(String text)
	{
		return text
			.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")
			.replace("\"", "&quot;");
	}
}
