package localreceiver.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The locations no web client may touch, and where each device's
 * functional folders live.
 *
 * <ul>
 *   <li>Each paired device's recycle bin and upload staging sit at
 *       {@code <dataDir>/<deviceId>/.trash} and {@code .uploads}
 *       (dataDir defaults to {@code ~/localReceiver}); the open-mode
 *       device uses {@code <dataDir>/.trash} and {@code .uploads}. They
 *       depend on neither the shared folder nor the device's folder, so
 *       the host changing either never moves them.</li>
 *   <li>The pre-v1.1 shared {@code .localreceiver-trash} and
 *       {@code .localreceiver-part} folders at the file root stay
 *       blocked.</li>
 *   <li>The config directory (TLS keys, device tokens) is blocked for
 *       every client, also through a symlink.</li>
 *   <li>Over plain HTTP everything is read-only except each paired
 *       device's {@code <dataDir>/<deviceId>/safe} folder, which the
 *       client sees as a virtual top-level folder {@code safe} and may
 *       read and write. Open mode has no safe folder; over HTTPS the
 *       normal per-device grants apply and there is no virtual
 *       folder.</li>
 * </ul>
 *
 * Names compare case-insensitively ({@link FolderNames#key}).
 */
final class SpecialPaths {
    static final String TRASH = ".trash";
    static final String UPLOADS = ".uploads";
    static final String SAFE = "safe";
    private static final String LEGACY_TRASH = ".localreceiver-trash";
    private static final String LEGACY_PART = ".localreceiver-part";

    private final Path fileRoot;
    private final Path configDir;
    private final Path dataDir;

    SpecialPaths(Path fileRoot, Path configDir, Path dataDir) {
        this.fileRoot = fileRoot;
        this.configDir = configDir.toAbsolutePath().normalize();
        this.dataDir = dataDir.toAbsolutePath().normalize();
    }

    Path trashDir(Devices.Device device) {
        return home(device).resolve(TRASH);
    }

    Path uploadsDir(Devices.Device device) {
        return home(device).resolve(UPLOADS);
    }

    /** The device's HTTP-writable folder, or null in open mode. */
    Path safeDir(Devices.Device device) {
        return isOpen(device) ? null : home(device).resolve(SAFE);
    }

    private static boolean isOpen(Devices.Device device) {
        return device.id().equals(Devices.OPEN.id());
    }

    static boolean plainHttp(com.sun.net.httpserver.HttpExchange ex) {
        return !(ex instanceof com.sun.net.httpserver.HttpsExchange);
    }

    /** Where a client path landed: the base it is relative to, and whether that is the safe folder. */
    record Where(Path base, Path target, boolean safe) {
    }

    /**
     * Resolves a decoded, '/'-separated client path against the device
     * root, or against the safe folder when the request is plain HTTP and
     * the first segment is {@code safe}. Null when the path escapes.
     */
    Where locate(com.sun.net.httpserver.HttpExchange ex, Devices.Device device, Path root,
            String rel) throws IOException {
        String clean = rel == null ? "" : rel.replaceFirst("^/+", "");
        Path safe = plainHttp(ex) ? safeDir(device) : null;
        if (safe != null && !clean.isEmpty()) {
            String[] parts = clean.split("/", 2);
            if (FolderNames.same(parts[0], SAFE)) {
                Files.createDirectories(safe);
                Path target = safe.resolve(parts.length > 1 ? parts[1] : "").normalize();
                return target.startsWith(safe)
                        ? new Where(safe, FolderNames.canonical(safe, target), true) : null;
            }
        }
        Path target = root.resolve(clean).normalize();
        return target.startsWith(root)
                ? new Where(root, FolderNames.canonical(root, target), false) : null;
    }

    /** Plain HTTP may only write inside the safe folder. */
    static boolean writable(com.sun.net.httpserver.HttpExchange ex, Where where) {
        return !plainHttp(ex) || where.safe();
    }

    /** Whether the device root listing gets the virtual safe folder. */
    boolean showsSafe(com.sun.net.httpserver.HttpExchange ex, Devices.Device device) {
        return plainHttp(ex) && safeDir(device) != null;
    }

    private Path home(Devices.Device device) {
        return isOpen(device) ? dataDir : dataDir.resolve(device.id());
    }

    /** True when no client may read, write or list p. */
    boolean forbidden(Path p) throws IOException {
        return forbidden(p, null);
    }

    /**
     * As {@link #forbidden(Path)}, except that the device's own safe
     * folder is open to it (over HTTPS too) when its working folder is
     * its device folder {@code <dataDir>/<deviceId>} or inside that safe
     * folder.
     */
    boolean forbidden(Path p, Devices.Device device) throws IOException {
        Path real = real(p);
        Path ownSafe = ownSafe(device);
        return legacy(p) || inside(p, configDir) || inside(real, real(configDir))
                || functional(p, dataDir, ownSafe) || functional(real, real(dataDir), real(ownSafe));
    }

    /**
     * As {@link #forbidden(Path, Devices.Device)}, and also when moving or
     * deleting p would carry the config dir or the device's own safe folder.
     */
    boolean forbiddenToMove(Path p, Devices.Device device) throws IOException {
        Path ownSafe = ownSafe(device);
        return forbidden(p, device) || inside(configDir, p) || inside(real(configDir), real(p))
                || (ownSafe != null && (inside(ownSafe, p) || inside(real(ownSafe), real(p))));
    }

    /** The device's safe folder when its working folder lets it see it there, else null. */
    private Path ownSafe(Devices.Device device) {
        if (device == null || isOpen(device)) {
            return null;
        }
        Path root = device.resolveRoot(fileRoot).toAbsolutePath().normalize();
        Path home = home(device);
        Path safe = home.resolve(SAFE);
        return FolderNames.samePath(root, home) || inside(root, safe) ? safe : null;
    }

    private boolean legacy(Path p) {
        if (!p.startsWith(fileRoot) || p.equals(fileRoot)) {
            return false;
        }
        String first = fileRoot.relativize(p).getName(0).toString();
        return FolderNames.same(first, LEGACY_TRASH) || FolderNames.same(first, LEGACY_PART);
    }

    /**
     * p is a .trash/.uploads/safe folder (or inside one) at dataDir or
     * dataDir/<id>; the safe folder at allowedSafe (when not null) does not count.
     */
    private static boolean functional(Path p, Path base, Path allowedSafe) {
        if (!inside(p, base)) {
            return false;
        }
        int depth = p.getNameCount() - base.getNameCount();
        for (int i = 0; i < Math.min(depth, 2); i++) {
            int end = base.getNameCount() + i;
            String name = p.getName(end).toString();
            if (FolderNames.same(name, SAFE) && allowedSafe != null
                    && FolderNames.samePath(p.getRoot().resolve(p.subpath(0, end + 1)), allowedSafe)) {
                continue;
            }
            if (FolderNames.same(name, TRASH) || FolderNames.same(name, UPLOADS)
                    || FolderNames.same(name, SAFE)) {
                return true;
            }
        }
        return false;
    }

    /** Whether p is prefix or below it, comparing segments case-insensitively. */
    private static boolean inside(Path p, Path prefix) {
        if (p == null || prefix == null || p.getNameCount() < prefix.getNameCount()
                || !String.valueOf(p.getRoot()).equalsIgnoreCase(String.valueOf(prefix.getRoot()))) {
            return false;
        }
        for (int i = 0; i < prefix.getNameCount(); i++) {
            if (!FolderNames.same(p.getName(i).toString(), prefix.getName(i).toString())) {
                return false;
            }
        }
        return true;
    }

    /** p with symlinks resolved through its deepest existing ancestor. */
    private static Path real(Path p) throws IOException {
        Path existing = p;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return null;
        }
        return existing.toRealPath().resolve(existing.relativize(p)).normalize();
    }

    /**
     * Moves source to target, falling back to copy-then-delete when they
     * sit on different drives (the data dir need not share the file
     * root's drive).
     */
    static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target);
            return;
        } catch (java.nio.file.DirectoryNotEmptyException e) {
            // a non-empty folder across drives: copy the tree instead
        }
        try (var walk = Files.walk(source)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path dest = target.resolve(source.relativize(p).toString());
                if (Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(p, dest, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS);
                }
            }
        }
        TrashHandler.deleteRecursively(source);
    }
}
