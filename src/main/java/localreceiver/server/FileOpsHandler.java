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
 * <p>Everything resolves strictly inside the device's subtree (403
 * otherwise) and refuses the root itself and the {@code .localreceiver-part}
 * staging area.
 */
public final class FileOpsHandler implements HttpHandler {
    private final Path fileRoot;
    private final java.util.function.Function<HttpExchange, Devices.Device> auth;

    public FileOpsHandler(Path fileRoot,
            java.util.function.Function<HttpExchange, Devices.Device> auth) {
        this.fileRoot = fileRoot;
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
            Path target = resolveSafe(root, q.get("path"));
            if (target == null) {
                UploadHandler.sendJson(ex, 400, "{\"error\":\"bad path\"}");
                return;
            }
            if (!device.canWriteSub(Devices.Device.firstSegment(root, target))) {
                UploadHandler.sendJson(ex, 403,
                        "{\"error\":\"writing to this folder is not allowed\"}");
                return;
            }
            if ("mkdir".equals(action)) {
                mkdir(ex, target);
                return;
            }
            if (!Files.exists(target)) {
                UploadHandler.sendJson(ex, 404, "{\"error\":\"not found\"}");
                return;
            }
            switch (action) {
                case "delete" -> delete(ex, device, target);
                case "rename" -> rename(ex, root, target, q.get("to"));
                case "move" -> move(ex, device, root, target, q.get("to"));
                default -> ex.sendResponseHeaders(404, -1);
            }
        }
    }

    private void mkdir(HttpExchange ex, Path target) throws IOException {
        if (Files.exists(target)) {
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

    /** Moves target into the {@code to} directory, auto-renaming on conflict. */
    private void move(HttpExchange ex, Devices.Device device, Path root, Path target, String to)
            throws IOException {
        Path destDir;
        if (to == null || to.isBlank()) {
            destDir = root;
        } else {
            destDir = resolveSafe(root, to);
            if (destDir == null) {
                UploadHandler.sendJson(ex, 400, "{\"error\":\"bad destination\"}");
                return;
            }
        }
        if (!Files.isDirectory(destDir)) {
            UploadHandler.sendJson(ex, 404, "{\"error\":\"destination is not a folder\"}");
            return;
        }
        if (!device.canWriteSub(Devices.Device.firstSegment(root, destDir))) {
            UploadHandler.sendJson(ex, 403,
                    "{\"error\":\"writing to this folder is not allowed\"}");
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
        for (int n = 2; Files.exists(destination); n++) {
            destination = destDir.resolve(base + " (" + n + ")" + extension);
        }
        Files.move(target, destination);
        UploadHandler.sendJson(ex, 200,
                "{\"name\":" + FilesHandler.quote(destination.getFileName().toString()) + "}");
    }

    /** Resolves a client path inside the device root, or null when unsafe. */
    private Path resolveSafe(Path root, String raw) {
        String clean = UploadHandler.sanitizePath(raw);
        if (clean == null) {
            return null;
        }
        Path target = root.resolve(clean).normalize();
        if (!target.startsWith(root) || target.equals(root)
                || target.startsWith(fileRoot.resolve(UploadHandler.PART_DIR))
                || target.startsWith(fileRoot.resolve(TrashHandler.DIR))) {
            return null;
        }
        return target;
    }

    /** "Delete" moves to the recycle bin — nothing is destroyed here. */
    private void delete(HttpExchange ex, Devices.Device device, Path target) throws IOException {
        TrashHandler.moveToTrash(fileRoot, device.id(), target);
        ex.sendResponseHeaders(204, -1);
    }

    private void rename(HttpExchange ex, Path root, Path target, String to) throws IOException {
        String newName = UploadHandler.sanitize(to);
        if (newName == null) {
            UploadHandler.sendJson(ex, 400, "{\"error\":\"bad name\"}");
            return;
        }
        Path destination = target.getParent().resolve(newName).normalize();
        if (!destination.startsWith(root)) {
            UploadHandler.sendJson(ex, 400, "{\"error\":\"bad name\"}");
            return;
        }
        if (Files.exists(destination)) {
            UploadHandler.sendJson(ex, 409, "{\"error\":\"name already exists\"}");
            return;
        }
        Files.move(target, destination);
        UploadHandler.sendJson(ex, 200, "{\"name\":" + FilesHandler.quote(newName) + "}");
    }
}
