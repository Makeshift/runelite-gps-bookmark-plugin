package com.gpsbookmark;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import net.runelite.api.coords.WorldPoint;

/**
 * Modal dialog used both to create a new bookmark and to edit an existing one.
 *
 * <p>For a new bookmark the X/Y/Plane fields are pre-filled with the player's
 * current world location (when available); for editing they are pre-filled
 * with the bookmark's existing values.</p>
 */
final class BookmarkDialog extends JDialog
{
	private final JTextField nameField = new JTextField(16);
	private final JTextField xField = new JTextField(6);
	private final JTextField yField = new JTextField(6);
	private final JTextField planeField = new JTextField(3);
	private final JTextArea notesField = new JTextArea(4, 16);

	private final GpsBookmarkPlugin plugin;
	private final GpsBookmark existing;

	private BookmarkDialog(Window owner, GpsBookmarkPlugin plugin, GpsBookmark existing)
	{
		super(owner, existing == null ? "Add bookmark" : "Edit bookmark", ModalityType.APPLICATION_MODAL);
		this.plugin = plugin;
		this.existing = existing;

		setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		setLayout(new BorderLayout(8, 8));

		add(buildForm(), BorderLayout.CENTER);
		add(buildButtons(), BorderLayout.SOUTH);

		populateFields();

		pack();
		setMinimumSize(new Dimension(320, getHeight()));
		setLocationRelativeTo(owner);
	}

	static void show(Component parent, GpsBookmarkPlugin plugin, GpsBookmark existing)
	{
		final Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
		final BookmarkDialog dialog = new BookmarkDialog(owner, plugin, existing);
		dialog.setVisible(true);
	}

	private JPanel buildForm()
	{
		final JPanel form = new JPanel(new GridBagLayout());
		form.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		final GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(2, 4, 2, 4);
		c.anchor = GridBagConstraints.WEST;
		c.fill = GridBagConstraints.HORIZONTAL;

		int row = 0;

		c.gridx = 0;
		c.gridy = row;
		c.weightx = 0;
		form.add(new JLabel("Name:"), c);
		c.gridx = 1;
		c.gridwidth = 3;
		c.weightx = 1;
		form.add(nameField, c);
		c.gridwidth = 1;
		row++;

		c.gridx = 0;
		c.gridy = row;
		c.weightx = 0;
		form.add(new JLabel("X:"), c);
		c.gridx = 1;
		c.weightx = 1;
		form.add(xField, c);
		c.gridx = 2;
		c.weightx = 0;
		form.add(new JLabel("Y:"), c);
		c.gridx = 3;
		c.weightx = 1;
		form.add(yField, c);
		row++;

		c.gridx = 0;
		c.gridy = row;
		c.weightx = 0;
		form.add(new JLabel("Plane:"), c);
		c.gridx = 1;
		c.weightx = 1;
		form.add(planeField, c);
		row++;

		c.gridx = 0;
		c.gridy = row;
		c.weightx = 0;
		c.anchor = GridBagConstraints.NORTHWEST;
		form.add(new JLabel("Notes:"), c);
		c.gridx = 1;
		c.gridwidth = 3;
		c.weightx = 1;
		c.weighty = 1;
		c.fill = GridBagConstraints.BOTH;
		notesField.setLineWrap(true);
		notesField.setWrapStyleWord(true);
		form.add(new JScrollPane(notesField), c);

		return form;
	}

	private JPanel buildButtons()
	{
		final JPanel buttons = new JPanel();
		buttons.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

		final JButton useCurrent = new JButton("Use current location");
		useCurrent.setToolTipText("Fill X/Y/Plane with the player's current world location");
		useCurrent.addActionListener(e -> fillFromPlayer());
		buttons.add(useCurrent);

		final JButton useTarget = new JButton("Use current target");
		if (plugin.getCurrentTarget() == null)
		{
			useTarget.setEnabled(false);
			useTarget.setToolTipText("No active Shortest Path target. Navigate to a bookmark first to set one.");
		}
		else
		{
			useTarget.setToolTipText("Fill X/Y/Plane with the current Shortest Path target");
			useTarget.addActionListener(e -> fillFromTarget());
		}
		buttons.add(useTarget);

		final JButton ok = new JButton(existing == null ? "Add" : "Save");
		ok.addActionListener(e -> onOk());
		buttons.add(ok);

		final JButton cancel = new JButton("Cancel");
		cancel.addActionListener(e -> dispose());
		buttons.add(cancel);

		getRootPane().setDefaultButton(ok);
		return buttons;
	}

	private void populateFields()
	{
		if (existing != null)
		{
			nameField.setText(existing.getName());
			xField.setText(Integer.toString(existing.getX()));
			yField.setText(Integer.toString(existing.getY()));
			planeField.setText(Integer.toString(existing.getPlane()));
			notesField.setText(existing.getNotes() == null ? "" : existing.getNotes());
		}
		else
		{
			// Pre-fill with 0 immediately so the fields are never blank, then
			// overwrite asynchronously once the client thread provides the real location.
			xField.setText("0");
			yField.setText("0");
			planeField.setText("0");
			fillFromPlayer();
		}
	}

	private void fillFromPlayer()
	{
		plugin.getPlayerLocationAsync(location ->
		{
			if (location != null)
			{
				xField.setText(Integer.toString(location.getX()));
				yField.setText(Integer.toString(location.getY()));
				planeField.setText(Integer.toString(location.getPlane()));
			}
		});
	}

	private void fillFromTarget()
	{
		final WorldPoint target = plugin.getCurrentTarget();
		if (target == null)
		{
			return;
		}
		xField.setText(Integer.toString(target.getX()));
		yField.setText(Integer.toString(target.getY()));
		planeField.setText(Integer.toString(target.getPlane()));
	}

	private void onOk()
	{
		final String name = nameField.getText() == null ? "" : nameField.getText().trim();
		if (name.isEmpty())
		{
			JOptionPane.showMessageDialog(this, "Name is required.", "Invalid input", JOptionPane.ERROR_MESSAGE);
			return;
		}

		final Integer x = parseInt(xField.getText(), "X");
		if (x == null)
		{
			return;
		}
		final Integer y = parseInt(yField.getText(), "Y");
		if (y == null)
		{
			return;
		}
		final Integer plane = parseInt(planeField.getText(), "Plane");
		if (plane == null)
		{
			return;
		}
		if (plane < 0 || plane > 3)
		{
			JOptionPane.showMessageDialog(this, "Plane must be between 0 and 3.", "Invalid input", JOptionPane.ERROR_MESSAGE);
			return;
		}

		final String notes = notesField.getText() == null ? "" : notesField.getText();

		if (existing == null)
		{
			plugin.addBookmark(new GpsBookmark(name, notes, x, y, plane));
		}
		else
		{
			existing.setName(name);
			existing.setNotes(notes);
			existing.setX(x);
			existing.setY(y);
			existing.setPlane(plane);
			plugin.updateBookmark(existing);
		}

		dispose();
	}

	private Integer parseInt(String text, String fieldName)
	{
		try
		{
			return Integer.parseInt(text == null ? "" : text.trim());
		}
		catch (NumberFormatException ex)
		{
			JOptionPane.showMessageDialog(this, fieldName + " must be a whole number.", "Invalid input", JOptionPane.ERROR_MESSAGE);
			return null;
		}
	}
}
