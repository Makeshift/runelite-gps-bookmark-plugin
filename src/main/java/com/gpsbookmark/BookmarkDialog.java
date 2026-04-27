package com.gpsbookmark;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import net.runelite.api.coords.WorldPoint;

/**
 * Modal dialog for creating or editing a {@link GpsBookmark}.
 *
 * <p>A {@link JDialog} is used (instead of {@link JOptionPane#showInputDialog}) because we need
 * to capture five fields plus a free-form notes area; {@code JOptionPane.showInputDialog} only
 * supports a single value.
 */
final class BookmarkDialog
{
	// Reasonable bounds for OSRS world coordinates / planes.
	private static final int MAX_COORD = 25_000;
	private static final int MAX_PLANE = 3;

	private BookmarkDialog()
	{
	}

	/**
	 * Show the dialog in "Add" mode, pre-filling coordinates from {@code prefill} (typically
	 * the player's current {@link WorldPoint}). When the user confirms, {@code onSave} is
	 * invoked on the EDT with the new bookmark.
	 */
	static void showAdd(Component parent, WorldPoint prefill, Consumer<GpsBookmark> onSave)
	{
		final int px = prefill != null ? prefill.getX() : 0;
		final int py = prefill != null ? prefill.getY() : 0;
		final int pp = prefill != null ? prefill.getPlane() : 0;
		show(parent, "Add bookmark", "", "", px, py, pp, (name, notes, x, y, plane) ->
			onSave.accept(GpsBookmark.create(name, notes, x, y, plane)));
	}

	/**
	 * Show the dialog in "Edit" mode, pre-filled with the existing bookmark's values.
	 */
	static void showEdit(Component parent, GpsBookmark existing, Consumer<GpsBookmark> onSave)
	{
		show(parent, "Edit bookmark", existing.getName(), existing.getNotes(),
			existing.getX(), existing.getY(), existing.getPlane(),
			(name, notes, x, y, plane) -> onSave.accept(
				new GpsBookmark(existing.getId(), name, notes, x, y, plane)));
	}

	@FunctionalInterface
	private interface SaveHandler
	{
		void save(String name, String notes, int x, int y, int plane);
	}

	private static void show(Component parent, String title,
		String initialName, String initialNotes,
		int initialX, int initialY, int initialPlane,
		SaveHandler onSave)
	{
		assert SwingUtilities.isEventDispatchThread();

		final Window owner = parent != null ? SwingUtilities.getWindowAncestor(parent) : null;
		final JDialog dialog = new JDialog(owner, title, JDialog.ModalityType.APPLICATION_MODAL);
		dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

		final JTextField nameField = new JTextField(initialName == null ? "" : initialName, 20);
		final JTextArea notesArea = new JTextArea(initialNotes == null ? "" : initialNotes, 5, 20);
		notesArea.setLineWrap(true);
		notesArea.setWrapStyleWord(true);
		final JScrollPane notesScroll = new JScrollPane(notesArea);
		notesScroll.setPreferredSize(new Dimension(240, 100));

		final JSpinner xSpinner = new JSpinner(new SpinnerNumberModel(clamp(initialX, 0, MAX_COORD), 0, MAX_COORD, 1));
		final JSpinner ySpinner = new JSpinner(new SpinnerNumberModel(clamp(initialY, 0, MAX_COORD), 0, MAX_COORD, 1));
		final JSpinner planeSpinner = new JSpinner(new SpinnerNumberModel(clamp(initialPlane, 0, MAX_PLANE), 0, MAX_PLANE, 1));

		final JPanel form = new JPanel(new GridBagLayout());
		form.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		final GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(4, 4, 4, 4);
		c.anchor = GridBagConstraints.WEST;
		c.fill = GridBagConstraints.HORIZONTAL;

		int row = 0;
		addRow(form, c, row++, "Name:", nameField);
		addRow(form, c, row++, "Notes:", notesScroll);
		addRow(form, c, row++, "X:", xSpinner);
		addRow(form, c, row++, "Y:", ySpinner);
		addRow(form, c, row++, "Plane:", planeSpinner);

		final JButton okButton = new JButton("OK");
		final JButton cancelButton = new JButton("Cancel");
		final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttons.add(okButton);
		buttons.add(cancelButton);

		okButton.addActionListener(e ->
		{
			final String name = nameField.getText().trim();
			if (name.isEmpty())
			{
				JOptionPane.showMessageDialog(dialog, "Name must not be empty.",
					"Invalid input", JOptionPane.ERROR_MESSAGE);
				return;
			}
			final int x = ((Number) xSpinner.getValue()).intValue();
			final int y = ((Number) ySpinner.getValue()).intValue();
			final int plane = ((Number) planeSpinner.getValue()).intValue();
			final String notes = notesArea.getText();
			dialog.dispose();
			onSave.save(name, notes, x, y, plane);
		});
		cancelButton.addActionListener(e -> dialog.dispose());

		dialog.getRootPane().setDefaultButton(okButton);

		dialog.setLayout(new BorderLayout());
		dialog.add(form, BorderLayout.CENTER);
		dialog.add(buttons, BorderLayout.SOUTH);
		dialog.pack();
		dialog.setLocationRelativeTo(parent);
		dialog.setVisible(true);
	}

	private static void addRow(JPanel form, GridBagConstraints c, int row, String label, Component field)
	{
		c.gridx = 0;
		c.gridy = row;
		c.weightx = 0;
		form.add(new JLabel(label), c);

		c.gridx = 1;
		c.weightx = 1;
		form.add(field, c);
	}

	private static int clamp(int v, int min, int max)
	{
		return Math.max(min, Math.min(max, v));
	}
}
