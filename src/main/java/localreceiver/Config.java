package localreceiver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Persistent settings, stored at {@code ~/.config/localreceiver/config.properties}
 * on every platform (resolved via {@code user.home}). The working directory
 * is exclusively the file area — configuration never lives there.
 */
public final class Config {
    // LOCALRECEIVER_CONFIG_DIR overrides the location (tests, portable setups).
    private static final Path DIR = System.getenv("LOCALRECEIVER_CONFIG_DIR") != null
            ? Path.of(System.getenv("LOCALRECEIVER_CONFIG_DIR"))
            : Path.of(System.getProperty("user.home"), ".config", "localreceiver");
    private static final Path FILE = DIR.resolve("config.properties");
    // LOCALRECEIVER_DATA_DIR overrides where device recycle bins and
    // upload staging live (default ~/localReceiver).
    private static final Path DATA_DIR = System.getenv("LOCALRECEIVER_DATA_DIR") != null
            ? Path.of(System.getenv("LOCALRECEIVER_DATA_DIR"))
            : Path.of(System.getProperty("user.home"), "localReceiver");

    /** Where each device's .trash and .uploads folders live. */
    public static Path dataDir() {
        return DATA_DIR;
    }

    /** The per-user config directory (also holds the TLS keystore). */
    public static Path dir() {
        return DIR;
    }

    private final Properties props = new Properties();

    private Config() {
    }

    public static Config load() {
        Config config = new Config();
        if (Files.exists(FILE)) {
            try (InputStream in = Files.newInputStream(FILE)) {
                config.props.load(in);
            } catch (IOException ignored) {
                // unreadable config falls back to defaults
            }
        }
        return config;
    }

    public void save() {
        try {
            Files.createDirectories(DIR);
            try (OutputStream out = Files.newOutputStream(FILE)) {
                props.store(out, "localReceiver settings");
            }
        } catch (IOException ignored) {
            // settings are a convenience; failing to persist them is not fatal
        }
    }

    public int getPort(int fallback) {
        try {
            return Integer.parseInt(props.getProperty("port", ""));
        } catch (NumberFormatException nfe) {
            return fallback;
        }
    }

    public void setPort(int port) {
        props.setProperty("port", String.valueOf(port));
    }

    /** Optional persisted file-root override; null when unset/invalid. */
    public Path getRoot() {
        String value = props.getProperty("root");
        if (value == null || value.isBlank()) {
            return null;
        }
        Path root = Path.of(value);
        return Files.isDirectory(root) ? root : null;
    }

    public void setRoot(Path root) {
        props.setProperty("root", root.toAbsolutePath().toString());
    }

    /** Browser file management (rename/delete). Default OFF. */
    public boolean getFileOps(boolean fallback) {
        String value = props.getProperty("fileOps");
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    public void setFileOps(boolean fileOps) {
        props.setProperty("fileOps", String.valueOf(fileOps));
    }

    /** Device pairing requirement. Default ON (session per device). */
    public boolean getPairing(boolean fallback) {
        String value = props.getProperty("pairing");
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    public void setPairing(boolean pairing) {
        props.setProperty("pairing", String.valueOf(pairing));
    }

    /** Browsable HTML directory listings under /files/. Default OFF. */
    public boolean getDirBrowse(boolean fallback) {
        String value = props.getProperty("dirBrowse");
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    public void setDirBrowse(boolean dirBrowse) {
        props.setProperty("dirBrowse", String.valueOf(dirBrowse));
    }

    public boolean getAutostart(boolean fallback) {
        String value = props.getProperty("autostart");
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    public void setAutostart(boolean autostart) {
        props.setProperty("autostart", String.valueOf(autostart));
    }

    public boolean getHttps(boolean fallback) {
        String value = props.getProperty("https");
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    public void setHttps(boolean https) {
        props.setProperty("https", String.valueOf(https));
    }
}
