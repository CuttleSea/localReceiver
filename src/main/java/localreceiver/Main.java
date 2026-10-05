package localreceiver;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Path;

import localreceiver.gui.ServerWindow;
import localreceiver.server.LocalReceiverServer;

/**
 * Entry point for the localReceiver server application.
 *
 * <p>The working directory the jar is started from becomes the file root
 * served under {@code /files/}. With a display available a Swing control
 * window opens; with {@code --headless} (or no display) the server starts
 * immediately and blocks until interrupted.
 *
 * <p>Arguments: {@code --port <n>} (default 4646), {@code --headless}.
 */
public final class Main {
    /** Shown in the window title and startup line; bump with pixi.toml. */
    public static final String VERSION = "1.1.1";
    public static final int DEFAULT_PORT = 4646;

    private Main() {
    }

    /**
     * Headless device management on stdin, the console counterpart of
     * the window's device list: {@code devices} lists the paired devices,
     * {@code remove <name>} revokes one at once (its session cookie stops
     * working). Without a console (stdin closed) the thread just ends.
     */
    private static void startConsole(LocalReceiverServer server) {
        System.out.println("Commands: devices | remove <name> | help");
        Thread console = new Thread(() -> {
            try (var in = new java.io.BufferedReader(new java.io.InputStreamReader(
                    System.in, java.nio.charset.StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null; ) {
                    consoleCommand(server, line.trim());
                }
            } catch (IOException ignored) {
                // console gone: keep serving
            }
        }, "console");
        console.setDaemon(true);
        console.start();
    }

    static void consoleCommand(LocalReceiverServer server, String line) {
        String[] parts = line.split("\\s+", 2);
        switch (parts[0]) {
            case "" -> { }
            case "devices" -> {
                var list = server.devices().list();
                if (list.isEmpty()) {
                    System.out.println("No paired devices.");
                }
                for (var d : list) {
                    System.out.println(d.name() + "  id " + d.id()
                            + "  folder " + (d.relPath().isEmpty() ? "(shared folder)" : d.relPath())
                            + "  " + (d.read() ? "read" : "no read")
                            + (d.write() ? "+write" : ", read-only"));
                }
            }
            case "remove" -> {
                String name = parts.length > 1 ? parts[1].trim() : "";
                var device = server.devices().list().stream()
                        .filter(d -> d.name().equals(name)).findFirst();
                if (device.isEmpty()) {
                    System.out.println("No device named \"" + name + "\". Type devices to list them.");
                } else {
                    server.devices().remove(device.get().id());
                    System.out.println("Removed " + name + "; it must pair again to connect.");
                }
            }
            case "help" -> System.out.println("devices         list paired devices\n"
                    + "remove <name>   revoke a device at once");
            default -> System.out.println("Unknown command. Commands: devices | remove <name> | help");
        }
    }

    private static void printPairingCode(LocalReceiverServer server) {
        String code = server.devices().newPairingCode();
        System.out.println("Pairing code: " + code + " (valid 10 minutes, pairs one device)"
                + " — open " + server.scheme() + "://<this-host>:" + server.getPort()
                + "/?pair=" + code);
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        // Must run before ANY AWT class loads (sun.java2d.uiScale is
        // read at toolkit init) — keep this the first statement.
        jacross.UiScale.autoApply();
        Config config = Config.load();
        int port = config.getPort(DEFAULT_PORT);
        boolean https = config.getHttps(true);
        boolean headless = false;
        boolean dirBrowse = config.getDirBrowse(false);
        boolean pairing = config.getPairing(true);
        boolean newDevicesWrite = false;
        Path rootFlag = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--headless" -> headless = true;
                case "--https" -> https = true;
                case "--http" -> https = false;
                // File management is always on since v0.22; per-device
                // permissions govern access. Accepted for compatibility.
                case "--fileops", "--no-fileops" ->
                        System.err.println(args[i] + " is obsolete: file management is"
                                + " always on; use per-device permissions instead");
                case "--browse" -> dirBrowse = true;
                case "--no-browse" -> dirBrowse = false;
                case "--pairing" -> pairing = true;
                case "--open" -> pairing = false;
                // New devices pair read-only; the host grants writing per
                // device, or starts with this flag to pair them read+write.
                case "--new-devices-read-write" -> newDevicesWrite = true;
                case "--root" -> rootFlag = Path.of(args[++i]);
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    System.err.println("Usage: java -jar localreceiver.jar [--port <n>] [--root <dir>]"
                            + " [--headless] [--https|--http]"
                            + " [--browse|--no-browse] [--pairing|--open]"
                            + " [--new-devices-read-write]");
                    System.exit(2);
                }
            }
        }

        // File-root precedence: --root flag, then the persisted chooser
        // selection, then the working directory the jar was started in.
        Path fileRoot = rootFlag != null ? rootFlag
                : config.getRoot() != null ? config.getRoot()
                : Path.of(System.getProperty("user.dir"));
        if (!java.nio.file.Files.isDirectory(fileRoot)) {
            System.err.println("Not a directory: " + fileRoot);
            System.exit(2);
        }
        LocalReceiverServer server = new LocalReceiverServer(fileRoot);
        server.setDirBrowseEnabled(dirBrowse);
        server.setPairingRequired(pairing);
        server.devices().setNewDeviceWrite(newDevicesWrite);

        if (headless || GraphicsEnvironment.isHeadless()) {
            try {
                server.start(port, https);
            } catch (java.net.BindException be) {
                int freePort = LocalReceiverServer.findFreePort();
                System.err.println("Port " + port + " is already in use; using free port " + freePort);
                server.start(freePort, https);
            }
            System.out.println("localReceiver v" + VERSION + " serving " + fileRoot
                    + " at " + server.scheme() + "://localhost:" + server.getPort() + "/"
                    + " on port " + server.getPort());
            String fingerprint = localreceiver.server.TlsSupport.caFingerprint(
                    localreceiver.server.TlsSupport.caCertificate(Config.dir()));
            if (fingerprint != null) {
                // The console is the trusted channel when there is no window.
                System.out.println("Certificate SHA-256: " + fingerprint);
                System.out.println("Before trusting the certificate on a device, check that the"
                        + " device's own certificate details show this SHA-256."
                        + " Never rely on a fingerprint shown by a web page.");
            }
            if (pairing) {
                // Each code pairs one device; a fresh code is printed as
                // soon as one is consumed.
                printPairingCode(server);
                server.devices().setOnChange(() -> printPairingCode(server));
            }
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
            startConsole(server);
            Thread.currentThread().join();
        } else {
            final int guiPort = port;
            final boolean guiHttps = https;
            // Platform probes and the embedded font load happen here,
            // off the EDT; the L&F installs on the EDT before any
            // component is constructed. Headless mode never runs this.
            final jacross.Tokens theme = jacross.JaCross.detect();
            javax.swing.SwingUtilities.invokeLater(() -> {
                jacross.JaCross.apply(theme);
                new ServerWindow(server, guiPort, guiHttps, config).setVisible(true);
            });
        }
    }
}
