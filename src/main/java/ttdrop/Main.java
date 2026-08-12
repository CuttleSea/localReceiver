package ttdrop;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Path;

import ttdrop.gui.ServerWindow;
import ttdrop.server.TtDropServer;

/**
 * Entry point for the ttDrop server application.
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
    public static final String VERSION = "0.23.0";
    public static final int DEFAULT_PORT = 4646;

    private Main() {
    }

    private static void printPairingCode(TtDropServer server) {
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
                case "--root" -> rootFlag = Path.of(args[++i]);
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    System.err.println("Usage: java -jar ttdrop.jar [--port <n>] [--root <dir>]"
                            + " [--headless] [--https|--http]"
                            + " [--browse|--no-browse] [--pairing|--open]");
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
        TtDropServer server = new TtDropServer(fileRoot);
        server.setDirBrowseEnabled(dirBrowse);
        server.setPairingRequired(pairing);

        if (headless || GraphicsEnvironment.isHeadless()) {
            try {
                server.start(port, https);
            } catch (java.net.BindException be) {
                int freePort = TtDropServer.findFreePort();
                System.err.println("Port " + port + " is already in use; using free port " + freePort);
                server.start(freePort, https);
            }
            System.out.println("ttDrop v" + VERSION + " serving " + fileRoot
                    + " at " + server.scheme() + "://localhost:" + server.getPort() + "/"
                    + " on port " + server.getPort());
            if (pairing) {
                // Each code pairs one device; a fresh code is printed as
                // soon as one is consumed.
                printPairingCode(server);
                server.devices().setOnChange(() -> printPairingCode(server));
            }
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
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
