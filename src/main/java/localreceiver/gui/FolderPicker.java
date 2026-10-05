package localreceiver.gui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;

/**
 * A folder-selection dialog drawn entirely by the JaCross L&F.
 * Exists because {@code BasicLookAndFeel} ships no usable
 * {@code FileChooserUI} — a {@code JFileChooser} renders blank under
 * JaCrossLaf. Navigation: double-click a folder to enter it, "Up" for
 * the parent, or type a path and press Enter. An optional lower bound
 * confines the choice to a subtree (used for per-device folders).
 */
public final class FolderPicker extends JPanel {
    private final Path lowerBound;
    private final JTextField pathField = new JTextField(28);
    private final DefaultListModel<String> entries = new DefaultListModel<>();
    private final JList<String> list = new JList<>(entries);
    private Path current;

    /**
     * Shows the modal dialog. Returns the chosen folder, or null on
     * cancel. {@code lowerBound} (nullable) confines navigation to a
     * subtree, itself included.
     */
    public static Path pick(java.awt.Window parent, String title, Path start, Path lowerBound) {
        FolderPicker picker = new FolderPicker(start, lowerBound);
        JDialog dialog = new JDialog(parent, title, java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        final Path[] result = {null};

        JButton okButton = new JButton("Select this folder");
        okButton.addActionListener(e -> {
            result[0] = picker.current;
            dialog.dispose();
        });
        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> dialog.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        buttons.add(cancelButton);
        buttons.add(okButton);

        dialog.setLayout(new BorderLayout());
        dialog.add(picker, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
        return result[0];
    }

    /** Public for the headless render test; use {@link #pick} in app code. */
    public FolderPicker(Path start, Path lowerBound) {
        this.lowerBound = lowerBound == null ? null : lowerBound.toAbsolutePath().normalize();
        setLayout(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 16, 0, 16));

        JButton upButton = new JButton("Up");
        upButton.setToolTipText("Go to the parent folder");
        upButton.addActionListener(e -> {
            Path parent = current.getParent();
            if (parent != null && withinBound(parent)) {
                navigate(parent);
            }
        });
        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.add(new JLabel("Folder:"), BorderLayout.WEST);
        top.add(pathField, BorderLayout.CENTER);
        top.add(upButton, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        list.setVisibleRowCount(10);
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && list.getSelectedValue() != null) {
                    navigate(current.resolve(list.getSelectedValue()));
                }
            }
        });
        list.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "descend");
        list.getActionMap().put("descend", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (list.getSelectedValue() != null) {
                    navigate(current.resolve(list.getSelectedValue()));
                }
            }
        });
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new java.awt.Dimension(420, 220));
        add(scroll, BorderLayout.CENTER);

        pathField.addActionListener(e -> {
            try {
                Path typed = Path.of(pathField.getText().trim()).toAbsolutePath().normalize();
                if (Files.isDirectory(typed) && withinBound(typed)) {
                    navigate(typed);
                    return;
                }
            } catch (java.nio.file.InvalidPathException ignored) {
                // fall through: restore the current path below
            }
            pathField.setText(current.toString());
        });

        Path startAt = start != null && Files.isDirectory(start) ? start.toAbsolutePath().normalize()
                : this.lowerBound != null ? this.lowerBound
                : Path.of(System.getProperty("user.home"));
        navigate(withinBound(startAt) ? startAt
                : this.lowerBound != null ? this.lowerBound : startAt);
    }

    private boolean withinBound(Path path) {
        return lowerBound == null || path.startsWith(lowerBound);
    }

    private void navigate(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        current = dir;
        pathField.setText(dir.toString());
        entries.clear();
        List<String> names = new ArrayList<>();
        try (var children = Files.list(dir)) {
            for (Path child : (Iterable<Path>) children.sorted()::iterator) {
                String name = child.getFileName().toString();
                if (Files.isDirectory(child) && !name.startsWith(".")) {
                    names.add(name);
                }
            }
        } catch (IOException ignored) {
            // unreadable directory: show it empty
        }
        names.forEach(entries::addElement);
    }

    public Path currentFolder() {
        return current;
    }
}
