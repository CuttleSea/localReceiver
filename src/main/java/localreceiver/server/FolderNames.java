package localreceiver.server;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.Locale;

/**
 * Names in the shared folder are case-insensitive on every platform.
 * Two names are the same when their {@link #key} matches: Unicode NFC,
 * trailing dots and spaces dropped (Windows ignores them), case folded.
 * Deny lists compare by key, client paths resolve to the existing entry
 * with the same key, and a new name that collides with an existing one
 * by key is treated as taken, so case-sensitive and case-insensitive
 * drives behave alike.
 */
public final class FolderNames {
    /** Orders and compares names by {@link #key}. */
    public static final Comparator<String> ORDER = Comparator.comparing(FolderNames::key);

    private FolderNames() {
    }

    public static String key(String name) {
        String n = Normalizer.normalize(name, Normalizer.Form.NFC);
        int end = n.length();
        while (end > 0 && (n.charAt(end - 1) == '.' || n.charAt(end - 1) == ' ')) {
            end--;
        }
        return n.substring(0, end).toLowerCase(Locale.ROOT);
    }

    public static boolean same(String a, String b) {
        return key(a).equals(key(b));
    }

    /** Whether a and b name the same location, comparing segments by key. */
    public static boolean samePath(java.nio.file.Path a, java.nio.file.Path b) {
        if (a == null || b == null || a.getNameCount() != b.getNameCount()
                || !String.valueOf(a.getRoot()).equalsIgnoreCase(String.valueOf(b.getRoot()))) {
            return false;
        }
        for (int i = 0; i < a.getNameCount(); i++) {
            if (!same(a.getName(i).toString(), b.getName(i).toString())) {
                return false;
            }
        }
        return true;
    }

    /** The existing entry of dir named like name, preferring an exact match; null if none. */
    static Path existing(Path dir, String name) throws IOException {
        Path exact = dir.resolve(name);
        if (Files.exists(exact, LinkOption.NOFOLLOW_LINKS)) {
            // The real on-disk name: on Windows an 8.3 short name
            // (PRIVAT~1) or another case opens the same entry, and deny
            // rules must see its real name (Private).
            Path real = exact.toRealPath(LinkOption.NOFOLLOW_LINKS).getFileName();
            return real == null ? exact : dir.resolve(real.toString());
        }
        if (!Files.isDirectory(dir)) {
            return null;
        }
        String wanted = key(name);
        Path found = null;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                String entryName = entry.getFileName().toString();
                if (key(entryName).equals(wanted)
                        && (found == null || entryName.compareTo(found.getFileName().toString()) < 0)) {
                    found = entry;
                }
            }
        }
        return found;
    }

    /** True when dir already holds an entry named like name. */
    static boolean taken(Path dir, String name) throws IOException {
        return existing(dir, name) != null;
    }

    /**
     * Rewrites target (already normalized and inside root) so each
     * segment that exists under another spelling uses the on-disk name.
     * Segments that do not exist are kept as given.
     */
    static Path canonical(Path root, Path target) throws IOException {
        if (target.equals(root) || !target.startsWith(root)) {
            return target;
        }
        Path current = root;
        boolean exists = true;
        for (Path segment : root.relativize(target)) {
            Path match = exists ? existing(current, segment.toString()) : null;
            exists = match != null;
            current = exists ? match : current.resolve(segment.toString());
        }
        return current;
    }
}
