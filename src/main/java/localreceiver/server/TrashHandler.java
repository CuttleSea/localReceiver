package localreceiver.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Properties;

/**
 * The recycle bin. Deleting via {@code /api/files/delete} moves the
 * entry into {@code <fileRoot>/.localreceiver-trash/<id>/item/<name>} with a
 * sidecar {@code meta.properties} (original path relative to the file
 * root, deleting device, timestamp) — nothing is destroyed until
 * purged. Items are visible only to the device that deleted them.
 *
 * <ul>
 *   <li>{@code GET /api/trash} — this device's items:
 *       {@code {"items":[{id,name,origPath,dir,size,deletedAt}]}}.</li>
 *   <li>{@code POST /api/trash/restore?id=} — moves the item back to
 *       its original folder (recreated if needed; " (n)" suffix on
 *       conflict). 403 when the original location is outside the
 *       device's subtree or write-denied.</li>
 *   <li>{@code POST /api/trash/purge?id=} — deletes one item forever.</li>
 * </ul>
 */
public final class TrashHandler implements HttpHandler {
    static final String DIR = ".localreceiver-trash";

    private final Path fileRoot;
    private final java.util.function.Function<HttpExchange, Devices.Device> auth;

    public TrashHandler(Path fileRoot,
            java.util.function.Function<HttpExchange, Devices.Device> auth) {
        this.fileRoot = fileRoot;
        this.auth = auth;
    }

    /** Moves target into the bin; returns the item id. */
    static String moveToTrash(Path fileRoot, String deviceId, Path target) throws IOException {
        String id = System.currentTimeMillis() + "-"
                + Integer.toHexString((int) (Math.random() * 0xFFFF) & 0xFFFF);
        Path itemDir = fileRoot.resolve(DIR).resolve(id);
        Files.createDirectories(itemDir.resolve("item"));
        Properties meta = new Properties();
        meta.setProperty("origPath",
                fileRoot.relativize(target).toString().replace('\\', '/'));
        meta.setProperty("device", deviceId);
        meta.setProperty("deletedAt", String.valueOf(System.currentTimeMillis()));
        try (OutputStream out = Files.newOutputStream(itemDir.resolve("meta.properties"))) {
            meta.store(out, null);
        }
        Files.move(target, itemDir.resolve("item").resolve(target.getFileName().toString()));
        return id;
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
            String action = ex.getRequestURI().getPath().substring("/api/trash".length());
            if (action.isEmpty() || action.equals("/")) {
                list(ex, device);
                return;
            }
            if (!"POST".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            Map<String, String> q = UploadHandler.query(ex);
            String id = q.get("id");
            Path itemDir = itemDirFor(device, id);
            if (itemDir == null) {
                UploadHandler.sendJson(ex, 404, "{\"error\":\"not found\"}");
                return;
            }
            switch (action) {
                case "/restore" -> restore(ex, device, itemDir);
                case "/purge" -> purge(ex, itemDir);
                default -> ex.sendResponseHeaders(404, -1);
            }
        }
    }

    /** The item's directory when it exists AND belongs to this device. */
    private Path itemDirFor(Devices.Device device, String id) throws IOException {
        if (id == null || !id.matches("[0-9]+-[0-9a-f]+")) {
            return null;
        }
        Path itemDir = fileRoot.resolve(DIR).resolve(id).normalize();
        if (!itemDir.startsWith(fileRoot.resolve(DIR)) || !Files.isDirectory(itemDir)) {
            return null;
        }
        Properties meta = loadMeta(itemDir);
        if (meta == null || !device.id().equals(meta.getProperty("device"))) {
            return null;
        }
        return itemDir;
    }

    private static Properties loadMeta(Path itemDir) throws IOException {
        Path metaFile = itemDir.resolve("meta.properties");
        if (!Files.exists(metaFile)) {
            return null;
        }
        Properties meta = new Properties();
        try (InputStream in = Files.newInputStream(metaFile)) {
            meta.load(in);
        }
        return meta;
    }

    private static Path payload(Path itemDir) throws IOException {
        Path item = itemDir.resolve("item");
        if (!Files.isDirectory(item)) {
            return null;
        }
        try (var children = Files.list(item)) {
            return children.findFirst().orElse(null);
        }
    }

    private void list(HttpExchange ex, Devices.Device device) throws IOException {
        StringBuilder json = new StringBuilder("{\"items\":[");
        Path trashRoot = fileRoot.resolve(DIR);
        boolean first = true;
        if (Files.isDirectory(trashRoot)) {
            try (var items = Files.list(trashRoot)) {
                for (Path itemDir : (Iterable<Path>) items.sorted(
                        Comparator.comparing(Path::getFileName).reversed())::iterator) {
                    Properties meta = loadMeta(itemDir);
                    if (meta == null || !device.id().equals(meta.getProperty("device"))) {
                        continue;
                    }
                    Path entry = payload(itemDir);
                    if (entry == null) {
                        continue;
                    }
                    if (!first) {
                        json.append(',');
                    }
                    first = false;
                    boolean isDir = Files.isDirectory(entry);
                    json.append("{\"id\":").append(FilesHandler.quote(
                                    itemDir.getFileName().toString()))
                            .append(",\"name\":").append(FilesHandler.quote(
                                    entry.getFileName().toString()))
                            .append(",\"origPath\":").append(FilesHandler.quote(
                                    meta.getProperty("origPath", "")))
                            .append(",\"dir\":").append(isDir)
                            .append(",\"size\":").append(isDir ? 0 : Files.size(entry))
                            .append(",\"deletedAt\":").append(
                                    meta.getProperty("deletedAt", "0"))
                            .append('}');
                }
            }
        }
        UploadHandler.sendJson(ex, 200, json.append("]}").toString());
    }

    private void restore(HttpExchange ex, Devices.Device device, Path itemDir)
            throws IOException {
        Properties meta = loadMeta(itemDir);
        Path entry = payload(itemDir);
        if (meta == null || entry == null) {
            UploadHandler.sendJson(ex, 404, "{\"error\":\"not found\"}");
            return;
        }
        Path target = fileRoot.resolve(meta.getProperty("origPath", "")).normalize();
        Path deviceRoot = device.resolveRoot(fileRoot);
        if (!target.startsWith(fileRoot) || !target.startsWith(deviceRoot)
                || !device.canWriteSub(Devices.Device.firstSegment(deviceRoot, target))) {
            UploadHandler.sendJson(ex, 403,
                    "{\"error\":\"the original location is not writable for this device\"}");
            return;
        }
        Files.createDirectories(target.getParent());
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        Path destination = target;
        for (int n = 2; Files.exists(destination); n++) {
            destination = target.getParent().resolve(base + " (" + n + ")" + extension);
        }
        Files.move(entry, destination);
        deleteRecursively(itemDir);
        UploadHandler.sendJson(ex, 200, "{\"name\":" + FilesHandler.quote(
                fileRoot.relativize(destination).toString().replace('\\', '/')) + "}");
    }

    private void purge(HttpExchange ex, Path itemDir) throws IOException {
        deleteRecursively(itemDir);
        ex.sendResponseHeaders(204, -1);
    }

    static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
