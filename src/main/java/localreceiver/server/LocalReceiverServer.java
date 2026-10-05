package localreceiver.server;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * The localReceiver HTTP server: PWA host, file host, and receiver in one.
 *
 * <p>Serves the PWA from resources embedded in the jar (never from disk)
 * and the file area from the working directory the jar was started in.
 */
public final class LocalReceiverServer {
    private final Path fileRoot;
    private HttpServer http;
    private boolean https;
    /** HTML directory listings under /files/; default OFF, toggleable while running. */
    private volatile boolean dirBrowseEnabled;
    /** Session-per-device pairing; default ON, toggleable while running. */
    private volatile boolean pairingRequired = true;
    private final Devices devices = new Devices(localreceiver.Config.dir());

    public LocalReceiverServer(Path fileRoot) {
        this.fileRoot = fileRoot.toAbsolutePath().normalize();
    }

    public Devices devices() {
        return devices;
    }

    /** The device a request acts as, or null when unpaired in pairing mode. */
    public Devices.Device device(com.sun.net.httpserver.HttpExchange ex) {
        return devices.authorize(ex, pairingRequired);
    }

    public boolean isPairingRequired() {
        return pairingRequired;
    }

    public void setPairingRequired(boolean required) {
        this.pairingRequired = required;
    }

    public synchronized void start(int port) throws IOException {
        start(port, false);
    }

    public synchronized void start(int port, boolean useHttps) throws IOException {
        if (http != null) {
            throw new IllegalStateException("server already running");
        }
        if (useHttps) {
            var server = com.sun.net.httpserver.HttpsServer.create(new InetSocketAddress(port), 0);
            server.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(
                    TlsSupport.sslContext(localreceiver.Config.dir())));
            http = server;
        } else {
            http = HttpServer.create(new InetSocketAddress(port), 0);
        }
        this.https = useHttps;
        http.createContext("/", new WebRootHandler());
        http.createContext("/files/", new FilesHandler(fileRoot,
                this::isDirBrowseEnabled, this::device));
        http.createContext("/api/upload/", new UploadHandler(fileRoot, this::device));
        http.createContext("/api/files/", new FileOpsHandler(fileRoot, this::device));
        http.createContext("/api/trash", new TrashHandler(fileRoot, this::device));
        http.createContext("/api/zip", new ZipHandler(fileRoot, this::device));
        http.createContext("/api/pair", new PairHandler(devices, fileRoot,
                this::isPairingRequired, this::isDirBrowseEnabled, this::scheme));
        http.createContext("/api/session", new PairHandler(devices, fileRoot,
                this::isPairingRequired, this::isDirBrowseEnabled, this::scheme));
        http.createContext("/qr.png", new QrPngHandler(this::scheme));
        http.createContext("/ca.crt", new CaCertHandler(TlsSupport.caCertificate(localreceiver.Config.dir())));
        http.createContext(CaCertHandler.FINGERPRINT_PATH,
                new CaCertHandler(TlsSupport.caCertificate(localreceiver.Config.dir())));
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.start();
    }

    public synchronized boolean isHttps() {
        return https;
    }

    public boolean isDirBrowseEnabled() {
        return dirBrowseEnabled;
    }

    public void setDirBrowseEnabled(boolean enabled) {
        this.dirBrowseEnabled = enabled;
    }

    /** URL scheme of the running server, for building shareable URLs. */
    public synchronized String scheme() {
        return https ? "https" : "http";
    }

    public synchronized void stop() {
        if (http != null) {
            http.stop(0);
            http = null;
        }
    }

    public synchronized boolean isRunning() {
        return http != null;
    }

    public synchronized int getPort() {
        return http == null ? -1 : http.getAddress().getPort();
    }

    public Path getFileRoot() {
        return fileRoot;
    }

    /**
     * Addresses other devices on the LAN can use to reach this server.
     * Primary source is {@link NetworkInterface} (the same data
     * {@code ip addr}/{@code ipconfig} report); when that yields nothing
     * (odd VPN/container setups), falls back to parsing those commands.
     */
    public static List<String> lanAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(nic.getInetAddresses())) {
                    if (addr.isSiteLocalAddress()) {
                        out.add(addr.getHostAddress());
                    }
                }
            }
        } catch (IOException ignored) {
            // fall through to the command-based fallback below
        }
        if (out.isEmpty()) {
            boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
            for (String[] cmd : windows
                    ? new String[][] {{"ipconfig"}}
                    : new String[][] {{"ip", "addr"}, {"ifconfig"}}) {
                out.addAll(parseIpv4FromCommand(cmd));
                if (!out.isEmpty()) {
                    break;
                }
            }
        }
        return out;
    }

    private static List<String> parseIpv4FromCommand(String[] command) {
        List<String> out = new ArrayList<>();
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String text = new String(process.getInputStream().readAllBytes());
            process.waitFor();
            var matcher = java.util.regex.Pattern
                    .compile("(?<![\\d.])((?:\\d{1,3}\\.){3}\\d{1,3})(?![\\d.])")
                    .matcher(text);
            while (matcher.find()) {
                String ip = matcher.group(1);
                if (!ip.startsWith("127.") && !ip.startsWith("0.") && !ip.startsWith("255.")
                        && !ip.endsWith(".255") && !out.contains(ip)) {
                    out.add(ip);
                }
            }
        } catch (IOException | InterruptedException ignored) {
            // command missing or failed: nothing to add
        }
        return out;
    }

    /** An OS-assigned free TCP port, for fallback when the chosen port is taken. */
    public static int findFreePort() throws IOException {
        try (var socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
