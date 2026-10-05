package localreceiver.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Color;
import java.io.IOException;
import java.net.BindException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

import localreceiver.Config;
import localreceiver.server.LocalReceiverServer;
import localreceiver.util.QrCode;

/**
 * Control window: file root, port choice, start/stop, an address picker
 * listing the machine's local IPs, and a QR code of the selected URL so
 * camera-equipped devices open the site by scanning.
 */
public final class ServerWindow extends JFrame {
    private LocalReceiverServer server;
    private final Config config;
    private final JTextField portField;
    private final javax.swing.JCheckBox httpsBox = new javax.swing.JCheckBox("HTTPS");
    private final javax.swing.JCheckBox autostartBox = new javax.swing.JCheckBox("Start on launch");
    private final javax.swing.JCheckBox dirBrowseBox = new javax.swing.JCheckBox("Allow directory browsing");
    private final javax.swing.JCheckBox pairingBox = new javax.swing.JCheckBox("Require device pairing");
    private final JPanel linksPanel = new JPanel();
    /** Shows the CA fingerprint while serving HTTPS; see rebuildLinks. */
    private final JPanel caPanel = new JPanel();
    /** How long a copied pairing link may sit on the clipboard. */
    private static final int CLIPBOARD_CLEAR_SECONDS = 60;
    /** Live pairing code shown in the Pair link; re-minted on use. */
    private String pairCode;
    /** Which link the QR panel shows: "app", "pair", or "files". */
    private String qrTarget = "app";
    private final JPanel devicesPanel = new JPanel();
    private final JButton toggleButton = new JButton("Start");
    private final JButton rootButton = new JButton("Change…");
    private final JLabel rootLabel = new JLabel();
    private final JLabel statusLabel = new JLabel("Stopped");
    private final JComboBox<String> addressBox = new JComboBox<>();
    private final QrPanel qrPanel = new QrPanel();

    public ServerWindow(LocalReceiverServer initialServer, int initialPort, boolean initialHttps, Config config) {
        super("localReceiver v" + localreceiver.Main.VERSION);
        this.server = initialServer;
        this.config = config;
        this.portField = new JTextField(String.valueOf(initialPort), 6);
        this.httpsBox.setSelected(initialHttps);
        this.httpsBox.setToolTipText(
                "Serve over TLS with a self-signed certificate (devices must accept it once)");
        this.autostartBox.setSelected(config.getAutostart(false));
        this.autostartBox.setToolTipText("Start the server automatically when localReceiver opens");
        this.dirBrowseBox.setSelected(server.isDirBrowseEnabled());
        this.dirBrowseBox.setToolTipText(
                "Show browsable folder listing pages when a /files/ URL is opened directly (off by default)");
        this.pairingBox.setSelected(server.isPairingRequired());
        this.pairingBox.setToolTipText(
                "Devices must pair with a one-time code before seeing anything (on by default)");

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        applyAppIcon();
        // Diagnosable theming: hovering the status label reveals which
        // font family actually loaded (the embedded Noto Sans TC, or a
        // fallback — see Fonts).
        statusLabel.setToolTipText("UI font: " + statusLabel.getFont().getFamily());
        JPanel main = new JPanel();
        main.setLayout(new BoxLayout(main, BoxLayout.Y_AXIS));
        main.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

        rootLabel.setText("File root: " + server.getFileRoot());
        rootLabel.setFont(rootLabel.getFont().deriveFont(Font.PLAIN));
        JPanel rootRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        rootRow.add(rootLabel);
        rootRow.add(rootButton);
        main.add(rootRow);
        main.add(Box.createVerticalStrut(8));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        controls.add(new JLabel("Port:"));
        controls.add(portField);
        controls.add(httpsBox);
        controls.add(autostartBox);
        controls.add(toggleButton);
        controls.add(statusLabel);
        main.add(controls);
        JPanel permissions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        permissions.add(dirBrowseBox);
        main.add(permissions);
        JPanel pairingRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        pairingRow.add(pairingBox);
        main.add(pairingRow);
        devicesPanel.setLayout(new BoxLayout(devicesPanel, BoxLayout.Y_AXIS));
        devicesPanel.setBorder(BorderFactory.createTitledBorder("Devices"));
        main.add(devicesPanel);
        main.add(Box.createVerticalStrut(8));

        JPanel addressRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        addressRow.add(new JLabel("Address:"));
        addressRow.add(addressBox);
        main.add(addressRow);
        main.add(Box.createVerticalStrut(4));
        linksPanel.setLayout(new BoxLayout(linksPanel, BoxLayout.Y_AXIS));
        main.add(linksPanel);
        caPanel.setLayout(new BoxLayout(caPanel, BoxLayout.Y_AXIS));
        main.add(caPanel);
        main.add(Box.createVerticalStrut(8));
        main.add(qrPanel);

        addressBox.setVisible(false);
        linksPanel.setVisible(false);
        caPanel.setVisible(false);
        qrPanel.setVisible(false);
        toggleButton.addActionListener(e -> toggle());
        rootButton.addActionListener(e -> chooseRoot());
        addressBox.addActionListener(e -> rebuildLinks());
        // All three take effect immediately, even while running.
        dirBrowseBox.addActionListener(e -> {
            server.setDirBrowseEnabled(dirBrowseBox.isSelected());
            config.setDirBrowse(dirBrowseBox.isSelected());
            config.save();
            rebuildLinks();
        });
        pairingBox.addActionListener(e -> {
            server.setPairingRequired(pairingBox.isSelected());
            config.setPairing(pairingBox.isSelected());
            config.save();
            if (pairingBox.isSelected() && server.isRunning()) {
                pairCode = server.devices().newPairingCode();
            }
            rebuildLinks();
        });
        server.devices().setOnChange(this::onDevicesChanged);
        rebuildDevices();

        add(main, BorderLayout.CENTER);
        pack();
        setLocationRelativeTo(null);

        if (autostartBox.isSelected()) {
            javax.swing.SwingUtilities.invokeLater(this::toggle);
        }
    }

    /** Window/taskbar icon: the dark-background variant (resources). */
    private void applyAppIcon() {
        try (java.io.InputStream in = ServerWindow.class.getResourceAsStream("/localreceiver/icon.png")) {
            if (in == null) {
                return;
            }
            java.awt.image.BufferedImage icon = javax.imageio.ImageIO.read(in);
            setIconImage(icon);
            if (java.awt.Taskbar.isTaskbarSupported()) {
                java.awt.Taskbar taskbar = java.awt.Taskbar.getTaskbar();
                if (taskbar.isSupported(java.awt.Taskbar.Feature.ICON_IMAGE)) {
                    taskbar.setIconImage(icon);
                }
            }
        } catch (Exception ignored) {
            // the default Java icon is a cosmetic fallback only
        }
    }

    /** Registry change: a pairing consumed the shown code — mint a
     *  fresh one and refresh both the devices panel and the links. */
    private void onDevicesChanged() {
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (server.isRunning() && pairingBox.isSelected()) {
                pairCode = server.devices().newPairingCode();
            }
            rebuildDevices();
            rebuildLinks();
        });
    }

    /** Rebuilds the per-device permission rows from the registry. */
    private void rebuildDevices() {
        devicesPanel.removeAll();
        java.util.List<localreceiver.server.Devices.Device> all = server.devices().list();
        if (all.isEmpty()) {
            JLabel none = new JLabel("No paired devices yet.");
            none.setFont(none.getFont().deriveFont(Font.PLAIN));
            devicesPanel.add(none);
        }
        for (localreceiver.server.Devices.Device device : all) {
            devicesPanel.add(deviceRow(device));
        }
        devicesPanel.revalidate();
        devicesPanel.repaint();
        pack();
    }

    private JPanel deviceRow(localreceiver.server.Devices.Device device) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        row.add(new JLabel(device.name()));
        JLabel pathLabel = new JLabel(
                device.relPath().isEmpty() ? "→ (everything)" : "→ " + device.relPath() + "/");
        pathLabel.setFont(pathLabel.getFont().deriveFont(Font.PLAIN));
        pathLabel.setToolTipText("The only folder this device can see —"
                + " independent of the device name, and several devices may share one");
        row.add(pathLabel);
        JButton folderButton = new JButton("Folder…");
        folderButton.setToolTipText("Choose which folder this device may access"
                + " (the shared folder itself grants everything)");
        folderButton.addActionListener(e -> chooseDeviceFolder(device));
        row.add(folderButton);
        JButton subButton = new JButton("Subfolders…");
        subButton.setToolTipText(
                "Choose which subfolders this device may read and write (all allowed by default)");
        subButton.addActionListener(e -> showSubfoldersDialog(device.id()));
        row.add(subButton);
        row.add(permissionBox("Read", "Allow downloads and listings", device.read(),
                v -> updateDevice(device.id(), d -> d.withRead(v))));
        row.add(permissionBox("Write", "Allow uploads (and rename/delete when enabled)",
                device.write(), v -> updateDevice(device.id(), d -> d.withWrite(v))));
        row.add(permissionBox("Browse", "Allow /files/ listing pages for this device",
                device.browse(), v -> updateDevice(device.id(), d -> d.withBrowse(v))));
        JButton renameButton = new JButton("Rename…");
        renameButton.setToolTipText("Rename this device (a-z, 0-9, _);"
                + " its folder is unaffected — use Folder… to change that");
        renameButton.addActionListener(e -> renameDevice(device));
        row.add(renameButton);
        JButton removeButton = new JButton("Remove");
        removeButton.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(this,
                    "Unpair \"" + device.name() + "\"? The device will need a new code.",
                    "localReceiver", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                server.devices().remove(device.id());
            }
        });
        row.add(removeButton);
        return row;
    }

    /** Rename a device; its folder is independent and stays put. */
    private void renameDevice(localreceiver.server.Devices.Device device) {
        String input = (String) JOptionPane.showInputDialog(this,
                "New name for \"" + device.name() + "\" (lower-case a-z, 0-9, _)."
                        + " Its folder does not change:",
                "localReceiver", JOptionPane.PLAIN_MESSAGE, null, null, device.name());
        if (input == null || input.equals(device.name())) {
            return;
        }
        String error = server.devices().rename(device.id(), input.trim());
        if (error != null) {
            String message = switch (error) {
                case "name" -> "Names may only use lower-case a-z, 0-9 and _ (1-32 chars).";
                case "taken" -> "That name is already used by another device.";
                default -> "Rename failed.";
            };
            JOptionPane.showMessageDialog(this, message, "localReceiver", JOptionPane.ERROR_MESSAGE);
        }
    }

    private javax.swing.JCheckBox permissionBox(String label, String tip, boolean value,
            java.util.function.Consumer<Boolean> onChange) {
        javax.swing.JCheckBox box = new javax.swing.JCheckBox(label, value);
        box.setToolTipText(tip);
        box.addActionListener(e -> onChange.accept(box.isSelected()));
        return box;
    }

    /** Restrict a device to a folder inside the shared root. */
    private void chooseDeviceFolder(localreceiver.server.Devices.Device device) {
        java.nio.file.Path fileRoot = server.getFileRoot();
        java.nio.file.Path chosen = FolderPicker.pick(this,
                "Folder \"" + device.name() + "\" may access (shared folder = everything)",
                device.resolveRoot(fileRoot), fileRoot);
        if (chosen == null) {
            return;
        }
        String rel = fileRoot.relativize(chosen).toString().replace('\\', '/');
        updateDevice(device.id(), d -> d.withRelPath(rel));
    }

    /** Applies a change to the latest registry state of a device. */
    private void updateDevice(String id,
            java.util.function.UnaryOperator<localreceiver.server.Devices.Device> change) {
        localreceiver.server.Devices.Device current = server.devices().get(id);
        if (current != null) {
            server.devices().update(change.apply(current));
        }
    }

    /**
     * Checklist of the device's top-level subfolders with Read/Write
     * boxes; everything is allowed unless unticked (deny list, so
     * folders created later are allowed automatically).
     */
    private void showSubfoldersDialog(String deviceId) {
        localreceiver.server.Devices.Device device = server.devices().get(deviceId);
        if (device == null) {
            return;
        }
        java.nio.file.Path deviceRoot = device.resolveRoot(server.getFileRoot());
        java.util.List<String> subs = new java.util.ArrayList<>();
        try (var children = java.nio.file.Files.list(deviceRoot)) {
            for (java.nio.file.Path child : (Iterable<java.nio.file.Path>) children.sorted()::iterator) {
                String name = child.getFileName().toString();
                if (java.nio.file.Files.isDirectory(child) && !name.startsWith(".")) {
                    subs.add(name);
                }
            }
        } catch (java.io.IOException ignored) {
            // unreadable device folder: show it empty
        }
        javax.swing.JDialog dialog = new javax.swing.JDialog(this,
                "Subfolders \"" + device.name() + "\" may use", false);
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        if (subs.isEmpty()) {
            panel.add(new JLabel("No subfolders yet — everything inside is readable and writable."));
        } else {
            panel.add(new JLabel("Untick to block; new subfolders are always allowed."));
            panel.add(Box.createVerticalStrut(6));
        }
        for (String sub : subs) {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
            JLabel nameLabel = new JLabel(sub + "/");
            row.add(nameLabel);
            row.add(permissionBox("Read", "Allow reading " + sub,
                    !device.denyRead().contains(sub),
                    v -> updateDevice(deviceId, d -> d.withDeny(
                            toggled(d.denyRead(), sub, !v), d.denyWrite()))));
            row.add(permissionBox("Write", "Allow writing into " + sub,
                    !device.denyWrite().contains(sub),
                    v -> updateDevice(deviceId, d -> d.withDeny(
                            d.denyRead(), toggled(d.denyWrite(), sub, !v)))));
            panel.add(row);
        }
        dialog.add(new javax.swing.JScrollPane(panel));
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private static java.util.Set<String> toggled(java.util.Set<String> set, String name,
            boolean deny) {
        java.util.Set<String> out = new java.util.TreeSet<>(localreceiver.server.FolderNames.ORDER);
        out.addAll(set);
        if (deny) {
            out.add(name);
        } else {
            out.remove(name);
        }
        return out;
    }

    /** Pick a new file root (only while stopped); persisted in config.
     *  Bounded to the working directory the jar was started from —
     *  sharing arbitrary folders above it is deliberately impossible. */
    private void chooseRoot() {
        java.nio.file.Path workingDir =
                java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        java.nio.file.Path start = server.getFileRoot().startsWith(workingDir)
                ? server.getFileRoot() : workingDir;
        java.nio.file.Path newRoot = FolderPicker.pick(this,
                "Choose the folder localReceiver shares (inside " + workingDir + ")", start, workingDir);
        if (newRoot == null) {
            return;
        }
        server = new LocalReceiverServer(newRoot);
        server.setDirBrowseEnabled(dirBrowseBox.isSelected());
        server.setPairingRequired(pairingBox.isSelected());
        server.devices().setOnChange(this::onDevicesChanged);
        rebuildDevices();
        config.setRoot(newRoot);
        config.save();
        rootLabel.setText("File root: " + server.getFileRoot());
        pack();
    }

    private void toggle() {
        if (server.isRunning()) {
            server.stop();
            statusLabel.setText("Stopped");
            toggleButton.setText("Start");
            portField.setEnabled(true);
            httpsBox.setEnabled(true);
            rootButton.setEnabled(true);
            addressBox.setVisible(false);
            linksPanel.setVisible(false);
            caPanel.setVisible(false);
            qrPanel.setVisible(false);
            pairCode = null;
            pack();
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException nfe) {
            JOptionPane.showMessageDialog(this, "Invalid port", "localReceiver", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            startOn(port);
        } catch (BindException be) {
            // Another program owns this port: offer an OS-assigned free one.
            offerFreePort(port);
        } catch (IOException ioe) {
            JOptionPane.showMessageDialog(this, "Could not start: " + ioe.getMessage(),
                    "localReceiver", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void offerFreePort(int takenPort) {
        try {
            int freePort = LocalReceiverServer.findFreePort();
            int choice = JOptionPane.showConfirmDialog(this,
                    "Port " + takenPort + " is already used by another program.\n"
                            + "Use free port " + freePort + " instead?\n"
                            + "(Or press No and enter a different port yourself.)",
                    "localReceiver — port in use", JOptionPane.YES_NO_OPTION);
            if (choice == JOptionPane.YES_OPTION) {
                portField.setText(String.valueOf(freePort));
                startOn(freePort);
            }
        } catch (IOException ioe) {
            JOptionPane.showMessageDialog(this, "Could not start: " + ioe.getMessage(),
                    "localReceiver", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void startOn(int port) throws IOException {
        server.start(port, httpsBox.isSelected());
        config.setPort(port);
        config.setHttps(httpsBox.isSelected());
        config.setAutostart(autostartBox.isSelected());
        config.save();
        statusLabel.setText("Running");
        toggleButton.setText("Stop");
        portField.setEnabled(false);
        httpsBox.setEnabled(false);
        rootButton.setEnabled(false);
        if (pairingBox.isSelected()) {
            pairCode = server.devices().newPairingCode();
        }

        List<String> addresses = new ArrayList<>(LocalReceiverServer.lanAddresses());
        addresses.add("localhost");
        addressBox.removeAllItems();
        for (String address : addresses) {
            addressBox.addItem(address);
        }
        addressBox.setSelectedIndex(0);
        addressBox.setVisible(true);
        linksPanel.setVisible(true);
        qrPanel.setVisible(true);
        rebuildLinks();
    }

    /**
     * The links panel: one row per shareable URL — the app, the
     * pairing link (only while pairing is required; its one-time code
     * is re-minted whenever a device uses it), and /files/ (only
     * while directory browsing is enabled). Every row is clickable
     * (default browser), copyable, and selectable into the QR panel.
     */
    private void rebuildLinks() {
        Object selected = addressBox.getSelectedItem();
        if (selected == null || !server.isRunning()) {
            return;
        }
        String base = server.scheme() + "://" + selected + ":" + server.getPort() + "/";
        linksPanel.removeAll();
        java.util.Map<String, String> urls = new java.util.LinkedHashMap<>();
        urls.put("app", base);
        if (pairingBox.isSelected() && pairCode != null) {
            urls.put("pair", base + "?pair=" + java.net.URLEncoder.encode(
                    pairCode, java.nio.charset.StandardCharsets.UTF_8));
        }
        if (dirBrowseBox.isSelected()) {
            urls.put("files", base + "files/");
        }
        if (!urls.containsKey(qrTarget)) {
            qrTarget = "app";
        }
        for (var entry : urls.entrySet()) {
            linksPanel.add(linkRow(entry.getKey(), entry.getValue()));
        }
        rebuildCaFingerprint();
        qrPanel.show(urls.get(qrTarget));
        linksPanel.revalidate();
        linksPanel.repaint();
        pack();
    }

    /**
     * Shows the CA certificate's SHA-256 fingerprint whenever a CA
     * exists (it is served at /ca.crt in either mode).
     *
     * <p>This window is the out-of-band channel that makes installing
     * the CA safe: an attacker on the network path can substitute the
     * certificate the device downloads and rewrite every page the
     * device sees, but cannot change what is printed here. So the
     * instruction to compare against the device's own certificate
     * screen is visible text here, not a tooltip or a web page.
     */
    private void rebuildCaFingerprint() {
        caPanel.removeAll();
        String fingerprint = localreceiver.server.TlsSupport.caFingerprint(
                localreceiver.server.TlsSupport.caCertificate(Config.dir()));
        caPanel.setVisible(fingerprint != null);
        if (fingerprint != null) {
            // Split in two so the 95-character fingerprint does not
            // stretch the window past a usable width.
            int half = fingerprint.length() / 2;
            JLabel label = new JLabel("<html>Certificate SHA-256:<br>"
                    + fingerprint.substring(0, half) + "<br>"
                    + fingerprint.substring(half) + "<br>"
                    + "<small>Before trusting the certificate on a device, check that the"
                    + "<br>device's own certificate details show this SHA-256."
                    + "<br>Never rely on a fingerprint shown by a web page.</small></html>");
            label.setFont(label.getFont().deriveFont(Font.PLAIN));
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
            row.add(label);
            JButton copyButton = new JButton("Copy");
            copyButton.setToolTipText("Copy the fingerprint");
            copyButton.addActionListener(e -> copyToClipboard(fingerprint, false));
            row.add(copyButton);
            caPanel.add(row);
        }
        caPanel.revalidate();
        caPanel.repaint();
    }

    private JPanel linkRow(String key, String url) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        boolean sensitive = "pair".equals(key);
        String title = switch (key) {
            case "pair" -> "Pair";
            case "files" -> "Files";
            default -> "App";
        };
        JLabel prefix = new JLabel(title + ":");
        row.add(prefix);
        JLabel link = new JLabel("<html><a href=\"" + url + "\">" + url + "</a></html>");
        link.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        link.setToolTipText(sensitive
                ? "Open in your default browser (the code is not passed to it — type it in)"
                : "Open in your default browser");
        link.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                openInBrowser(url);
            }
        });
        row.add(link);
        JButton copyButton = new JButton("Copy");
        copyButton.setToolTipText(sensitive
                ? "Copy this link — it carries a live pairing code, so the clipboard is"
                        + " cleared after " + CLIPBOARD_CLEAR_SECONDS + " seconds"
                : "Copy this link");
        copyButton.addActionListener(e -> copyToClipboard(url, sensitive));
        row.add(copyButton);
        javax.swing.JToggleButton qrButton = new javax.swing.JToggleButton("QR");
        qrButton.setToolTipText("Show this link as the QR code below");
        qrButton.setSelected(key.equals(qrTarget));
        qrButton.addActionListener(e -> {
            qrTarget = key;
            rebuildLinks();
        });
        row.add(qrButton);
        return row;
    }

    /**
     * Opens a URL in the user's default browser, with any live pairing
     * code stripped first.
     *
     * <p>A process's command line is readable by every other local user
     * ({@code ps}, {@code /proc/<pid>/cmdline}), and browsers and
     * {@code xdg-open} wrappers keep the URL in their own long-lived
     * argv — so handing the one-time code to a subprocess would publish
     * it to anyone on the machine for the whole ten-minute window. The
     * code stays on screen instead, where the operator reads it and
     * types it into the device.
     */
    private void openInBrowser(String url) {
        String safe = stripPairCode(url);
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(safe));
            } else {
                // Linux desktops without java.awt.Desktop browse support.
                String os = System.getProperty("os.name", "").toLowerCase();
                String[] cmd = os.contains("win")
                        ? new String[] {"rundll32", "url.dll,FileProtocolHandler", safe}
                        : os.contains("mac") ? new String[] {"open", safe}
                        : new String[] {"xdg-open", safe};
                new ProcessBuilder(cmd).start();
            }
        } catch (IOException ioe) {
            JOptionPane.showMessageDialog(this, "Could not open browser: " + ioe.getMessage(),
                    "localReceiver", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (!safe.equals(url) && pairCode != null) {
            JOptionPane.showMessageDialog(this,
                    "Enter this pairing code on the page that just opened:\n\n"
                            + pairCode + "\n\n"
                            + "It is not passed to the browser, because other users of this\n"
                            + "computer could read it from the browser's command line.",
                    "localReceiver", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /**
     * The URL without any {@code pair} query parameter, keeping any
     * other parameters and the path intact. Public so the headless
     * link-safety test can cover it without a display.
     */
    public static String stripPairCode(String url) {
        int mark = url.indexOf('?');
        if (mark < 0) {
            return url;
        }
        StringBuilder kept = new StringBuilder();
        for (String part : url.substring(mark + 1).split("&")) {
            if (part.isEmpty() || part.equals("pair") || part.startsWith("pair=")) {
                continue;
            }
            if (!kept.isEmpty()) {
                kept.append('&');
            }
            kept.append(part);
        }
        String base = url.substring(0, mark);
        return kept.isEmpty() ? base : base + "?" + kept;
    }

    /**
     * Copies text to the clipboard; a credential-bearing link is wiped
     * again after {@link #CLIPBOARD_CLEAR_SECONDS} so clipboard history
     * tools and other local processes have only a short window to read
     * it. The wipe is skipped if the user has since copied something
     * else — clearing that would be destroying their data.
     */
    private void copyToClipboard(String text, boolean sensitive) {
        var clipboard = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(new java.awt.datatransfer.StringSelection(text), null);
        if (!sensitive) {
            return;
        }
        javax.swing.Timer timer =
                new javax.swing.Timer(CLIPBOARD_CLEAR_SECONDS * 1000, e -> clearClipboardIf(text));
        timer.setRepeats(false);
        timer.start();
    }

    private void clearClipboardIf(String text) {
        try {
            var clipboard = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
            Object current = clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            if (text.equals(current)) {
                clipboard.setContents(new java.awt.datatransfer.StringSelection(""), null);
            }
        } catch (Exception ignored) {
            // clipboard busy, empty, or holding a non-text flavour: leave it
        }
    }

    /** Paints a QR code, 4x4 px per module plus the standard quiet zone. */
    private static final class QrPanel extends JComponent {
        private static final int SCALE = 4;
        private static final int QUIET = 4;
        private boolean[][] matrix;

        void show(String text) {
            matrix = QrCode.encode(text);
            int px = (matrix.length + 2 * QUIET) * SCALE;
            Dimension d = new Dimension(px, px);
            setPreferredSize(d);
            setMinimumSize(d);
            setMaximumSize(d);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (matrix == null) {
                return;
            }
            int px = (matrix.length + 2 * QUIET) * SCALE;
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, px, px);
            g.setColor(Color.BLACK);
            for (int y = 0; y < matrix.length; y++) {
                for (int x = 0; x < matrix.length; x++) {
                    if (matrix[y][x]) {
                        g.fillRect((x + QUIET) * SCALE, (y + QUIET) * SCALE, SCALE, SCALE);
                    }
                }
            }
        }
    }
}
