package localreceiver.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

/**
 * File management under {@code /api/files/}, driven by the PWA's file
 * manager. Always enabled server-wide since v0.22 — access is governed
 * entirely by the per-device write grant and subfolder deny lists.
 *
 * <ul>
 *   <li>{@code POST /api/files/delete?path=} — deletes a file, or a
 *       directory recursively.</li>
 *   <li>{@code POST /api/files/rename?path=&to=} — renames within the
 *       same parent directory; {@code to} is a single sanitized name.
 *       409 when the new name already exists.</li>
 *   <li>{@code POST /api/files/mkdir?path=} — creates a folder
 *       (parents included). 409 when a file blocks the path.</li>
 *   <li>{@code POST /api/files/move?path=&to=} — moves a file or
 *       folder into the target directory ({@code to}, "" = the device
 *       root); on a name conflict the moved entry is renamed with a
 *       " (n)" suffix. Returns the final name.</li>
 * </ul>
 *
 * <p>Everything resolves strictly inside the device's subtree and
 * refuses the root itself and the {@link SpecialPaths}. Paths the
 * device may not reach (outside, off limits, deny-listed) answer 404
 * exactly like missing ones, so they reveal nothing.
 * Delete, rename and move need read access to the entry's folder; a
 * renamed top-level folder carries its deny rules to the new name.
 */
public final class FileOpsHandler implements HttpHandler {
    private static final String HTTP_READ_ONLY =
            "the shared folder is read-only over HTTP; use the safe folder";
    private static final String RULES_STAY =
            "this folder has access rules; it can only be renamed where it is";
    private final Path fileRoot;
    private final SpecialPaths special;
    private final Devices devices;
    private final java.util.function.Function<HttpExchange, Devices.Device> auth;

    public FileOpsHandler(Path fileRoot, SpecialPaths special, Devices devices,
            java.util.function.Function<HttpExchange, Devices.Device> auth) {
        this.fileRoot = fileRoot;
        this.special = special;
        this.devices = devices;
        this.auth = auth;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try (ex) {
            Devices.Device device = auth.apply(ex);
            if (device == null) {
                UploadHandler.sendJson(ex, 401, "{\"error\":\"not paired\"}");
                return;
            }
            if (!device.write()) {
                UploadHandler.sendJson(ex, 403,
                        "{\"error\":\"file management is not allowed for this device\"}");
                return;
            }
            if (!"POST".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            Path root = device.resolveRoot(fileRoot);
            String action = ex.getRequestURI().getPath().substring("/api/files/".length());
            Map<String, String> q = UploadHandler.query(ex);
            SpecialPaths.Where where = resolve(ex, device, root, q.get("path"));
            if (where == null) {
                UploadHandler.sendJson(ex, 404, "{\"error\":\"not found\"}");
                return;
            }
            if (!SpecialPaths.writable(ex, where)) {
                UploadHandler.sendJson(ex, 403, "{\"error\":\"" + HTTP_READ_ONLY + "\"}");
                return;
            }
            Path target = where.target();
            boolean shared = !where.safe();
            if (shared && !device.canWriteSub(Devices.Device.firstSegment(root, target))) {
                UploadHandler.sendJson(ex, 404, "{\"error\":\"not found\"}");
                return;
            }
            if ("mkdir".equals(action)) {
                mkdir(ex, target);
                return;
            }
            // Deleting, renaming or moving an entry carries its content
            // elsewhere, so it needs read access to its folder too.
            if (shared && !device.canReadSub(Devices.Device.firstSegment(root, target))) {
                UploadHandler.sendJson(ex, 404, "{\"error\":\"not found\"}");
                return;
            }
            if (!Files.exists(target)) {
                UploadHandler.sendJson(ex, 404, "{\"error\":\"not found\"}");
                return;
            }
            if (shared && special.forbiddenToMove(target, device)) {
                UploadHandler.sendJson(ex, 404, "{\"error\":\"not found\"}");
                return;
            }
            if (shared && devices.isWorkingFolderOrAbove(fileRoot, target)) {
                UploadHandler.sendJson(ex, 403,
                        "{\"error\":\"a device's working folder cannot be moved or renamed\"}");
                return;
            }
            switch (action) {
                case "delete" -> delete(ex, device, where);
                case "rename" -> rename(ex, device, where, q.get("to"));
                case "move" -> move(ex, device, root, where, q.get("to"));
                default -> ex.sendResponseHeaders(404, -1);
            }
        }
    }

    private void mkdir(HttpExchange ex, Path target) throws IOException {
        if (FolderNames.taken(target.getParent(), target.getFileName().toString())) {
            UploadHandler.sendJson(ex, 409, "{\"error\":\"already exists\"}");
            return;
        }
        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            UploadHandler.sendJson(ex, 409, "{\"error\":\"could not create folder\"}");
            return;
        }
        ex.sendResponseHeaders(204, -1);
    }

    /** Moves the entry into the {@code to} directory, auto-renaming on conflict. */
    private void move(HttpExchange ex, Devices.Device device, Path root,
            SpecialPaths.Where where, String to) throws IOException {
        Path target = where.target();
        String cleanTo = to == null || to.isBlank() ? "" : UploadHandler.sanitizePath(to);
        SpecialPaths.Where dest = cleanTo == null ? null
                : special.locate(ex, device, root, cleanTo);
        if (dest == null || (!dest.safe() && special.forbidden(dest.target(), device))) {
            UploadHandler.sendJson(ex, 404, "{\"error\":\"destination not found\"}");
            return;
        }
        if (!SpecialPaths.writable(ex, dest)) {
            UploadHandler.sendJson(ex, 403, "{\"error\":\"" + HTTP_READ_ONLY + "\"}");
            return;
        }
        Path destDir = dest.target();
        if (!Files.isDirectory(destDir)) {
            UploadHandler.sendJson(ex, 404, "{\"error\":\"destination is not a folder\"}");
            return;
        }
        if (!dest.safe() && !device.canWriteSub(Devices.Device.firstSegment(root, destDir))) {
            UploadHandler.sendJson(ex, 404, "{\"error\":\"not found\"}");
            return;
        }
        // A folder must never move into itself or its own subtree.
        if (Files.isDirectory(target) && destDir.startsWith(target)) {
            UploadHandler.sendJson(ex, 400, "{\"error\":\"cannot move a folder into itself\"}");
            return;
        }
        String name = target.getFileName().toString();
        Path destination = destDir.resolve(name);
        if (destination.equals(target)) {
            UploadHandler.sendJson(ex, 200, "{\"name\":" + FilesHandler.quote(name) + "}");
            return;
        }
        // Conflict: rename with a " (n)" suffix before the extension.
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int n = 2; FolderNames.taken(destDir, destination.getFileName().toString()); n++) {
            destination = destDir.resolve(base + " (" + n + ")" + extension);
        }
        if (!dest.safe() && special.forbidden(destination, device)) {
            UploadHandler.sendJson(ex, 404, "{\"error\":\"destination not found\"}");
            return;
        }
        if (!devices.denyCanFollow(fileRoot, target, destination)) {
            UploadHandler.sendJson(ex, 409, "{\"error\":\"" + RULES_STAY + "\"}");
            return;
        }
        Files.move(target, destination);
        devices.retargetDeny(fileRoot, target, destination);
        UploadHandler.sendJson(ex, 200,
                "{\"name\":" + FilesHandler.quote(destination.getFileName().toString()) + "}");
    }

    /**
     * Resolves a client path inside the device root (or, over plain HTTP,
     * the safe folder), or null when unsafe, off limits, or the base itself.
     */
    private SpecialPaths.Where resolve(HttpExchange ex, Devices.Device device, Path root,
            String raw) throws IOException {
        String clean = UploadHandler.sanitizePath(raw);
        if (clean == null) {
            return null;
        }
        SpecialPaths.Where where = special.locate(ex, device, root, clean);
        if (where == null || where.target().equals(where.base())
                || (!where.safe() && special.forbidden(where.target(), device))) {
            return null;
        }
        return where;
    }

    /** "Delete" moves to the recycle bin — nothing is destroyed here. */
    private void delete(HttpExchange ex, Devices.Device device, SpecialPaths.Where where)
            throws IOException {
        TrashHandler.moveToTrash(special.trashDir(device), where.safe() ? where.base() : fileRoot,
                where.safe() ? TrashHandler.AREA_SAFE : null, device.id(), where.target());
        ex.sendResponseHeaders(204, -1);
    }

    private void rename(HttpExchange ex, Devices.Device device, SpecialPaths.Where where,
            String to) throws IOException {
        Path target = where.target();
        String newName = UploadHandler.sanitize(to);
        if (newName == null) {
            UploadHandler.sendJson(ex, 400, "{\"error\":\"bad name\"}");
            return;
        }
        Path destination = target.getParent().resolve(newName).normalize();
        if (!destination.startsWith(where.base())) {
            UploadHandler.sendJson(ex, 400, "{\"error\":\"bad name\"}");
            return;
        }
        if (FolderNames.taken(destination.getParent(), newName)) {
            UploadHandler.sendJson(ex, 409, "{\"error\":\"name already exists\"}");
            return;
        }
        if (!where.safe() && special.forbidden(destination, device)) {
            UploadHandler.sendJson(ex, 400, "{\"error\":\"bad name\"}");
            return;
        }
        Files.move(target, destination);
        devices.retargetDeny(fileRoot, target, destination);
        UploadHandler.sendJson(ex, 200, "{\"name\":" + FilesHandler.quote(newName) + "}");
    }
}
