package localreceiver.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Chunked, resumable upload endpoint under {@code /api/upload/}.
 *
 * <p>Protocol (all metadata via query parameters, chunk bodies raw):
 * <ul>
 *   <li>{@code POST /api/upload/init?key=&name=&size=&chunkSize=} —
 *       create or find the staging area; returns
 *       {@code {"key":..,"chunkCount":n,"have":[..]}} where {@code have}
 *       lists chunk indexes already stored (resume support).</li>
 *   <li>{@code PUT /api/upload/chunk?key=&index=n} — store one chunk;
 *       written to a temp file and atomically renamed, so concurrent
 *       chunk uploads and crashes are safe.</li>
 *   <li>{@code GET /api/upload/status?key=} — same body as init's
 *       response, without creating anything.</li>
 *   <li>{@code POST /api/upload/complete?key=} — assemble chunks into
 *       the final file in the file root (collision-safe name), delete
 *       staging; returns {@code {"name":..}}.</li>
 * </ul>
 *
 * <p>Staging lives in the device's {@link SpecialPaths#uploadsDir} so
 * partial transfers survive server restarts; the finished file appears
 * at its destination with an atomic rename (after a copy when staging is
 * on another drive). The key is a client-derived stable identifier,
 * restricted to lowercase hex.
 */
public final class UploadHandler implements HttpHandler {
    private static final Pattern KEY = Pattern.compile("[a-f0-9]{8,64}");
    private static final long MAX_CHUNK_SIZE = 64L * 1024 * 1024;

    private static final String HTTP_READ_ONLY =
            "the shared folder is read-only over HTTP; use the safe folder";

    private final Path fileRoot;
    private final SpecialPaths special;
    private final java.util.function.Function<HttpExchange, Devices.Device> auth;

    public UploadHandler(Path fileRoot, SpecialPaths special,
            java.util.function.Function<HttpExchange, Devices.Device> auth) {
        this.fileRoot = fileRoot;
        this.special = special;
        this.auth = auth;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String action = ex.getRequestURI().getPath().substring("/api/upload/".length());
            Map<String, String> q = query(ex);
            String key = q.get("key");
            if (key == null || !KEY.matcher(key).matches()) {
                sendJson(ex, 400, "{\"error\":\"bad key\"}");
                return;
            }
            Devices.Device device = auth.apply(ex);
            if (device == null) {
                sendJson(ex, 401, "{\"error\":\"not paired\"}");
                return;
            }
            if (!device.write()) {
                sendJson(ex, 403, "{\"error\":\"uploads are not allowed for this device\"}");
                return;
            }
            // Staging lives in the device's own .uploads folder, so
            // same-keyed transfers from different devices never collide
            // and one device cannot touch another's staging.
            Path staging = special.uploadsDir(device).resolve(key);
            Path deviceRoot = device.resolveRoot(fileRoot);
            switch (action) {
                case "init" -> init(ex, key, staging, device, deviceRoot, q);
                case "chunk" -> chunk(ex, staging, q);
                case "status" -> status(ex, key, staging);
                case "complete" -> complete(ex, staging, device, deviceRoot);
                case "abort" -> abort(ex, staging);
                default -> ex.sendResponseHeaders(404, -1);
            }
        }
    }

    private void init(HttpExchange ex, String key, Path staging, Devices.Device device,
            Path deviceRoot, Map<String, String> q) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            return;
        }
        // "path" carries a folder-upload relative path ("dir/sub/file.txt",
        // forward slashes as browsers produce); "name" alone is a flat file.
        String name = sanitizePath(q.containsKey("path") ? q.get("path") : q.get("name"));
        long size;
        long chunkSize;
        try {
            size = Long.parseLong(q.getOrDefault("size", ""));
            chunkSize = Long.parseLong(q.getOrDefault("chunkSize", ""));
        } catch (NumberFormatException nfe) {
            sendJson(ex, 400, "{\"error\":\"bad size\"}");
            return;
        }
        if (name == null || size < 0 || chunkSize <= 0 || chunkSize > MAX_CHUNK_SIZE) {
            sendJson(ex, 400, "{\"error\":\"bad parameters\"}");
            return;
        }
        // Over plain HTTP the shared folder is read-only: uploads land in
        // the device's safe folder (none in open mode).
        if (SpecialPaths.plainHttp(ex)) {
            if (special.safeDir(device) == null) {
                sendJson(ex, 403, "{\"error\":\"" + HTTP_READ_ONLY + "\"}");
                return;
            }
            if (!FolderNames.same(name.split("/", 2)[0], SpecialPaths.SAFE)) {
                name = SpecialPaths.SAFE + "/" + name;
            }
        }
        SpecialPaths.Where where = special.locate(ex, device, deviceRoot, name);
        if (where == null || where.target().equals(where.base())) {
            sendJson(ex, 400, "{\"error\":\"bad parameters\"}");
            return;
        }
        if (!where.safe() && (!device.canWriteSub(Devices.Device.firstSegment(deviceRoot, where.target()))
                || offLimits(device, deviceRoot, name))) {
            sendJson(ex, 404, "{\"error\":\"folder not found\"}");
            return;
        }
        // Optional original modification time (millis): re-applied to
        // the assembled file so uploads keep their real timestamp.
        long mtime = 0;
        try {
            mtime = Long.parseLong(q.getOrDefault("mtime", "0"));
        } catch (NumberFormatException ignored) {
            // absent or malformed: keep the assembly time
        }
        Path metaFile = staging.resolve("meta.properties");
        Properties meta = new Properties();
        if (Files.exists(metaFile)) {
            try (InputStream in = Files.newInputStream(metaFile)) {
                meta.load(in);
            }
            // A key collision with different parameters is a new transfer:
            // wipe the stale staging area and start over.
            if (!String.valueOf(size).equals(meta.getProperty("size"))
                    || !String.valueOf(chunkSize).equals(meta.getProperty("chunkSize"))) {
                deleteRecursively(staging);
                meta.clear();
            }
        }
        Files.createDirectories(staging);
        // The working folder in force when the transfer started: a change
        // by the host applies once this upload has finished (a resumed
        // init keeps the original).
        if (meta.getProperty("root") == null) {
            meta.setProperty("root", deviceRoot.toString());
        }
        meta.setProperty("name", name);
        meta.setProperty("size", String.valueOf(size));
        meta.setProperty("chunkSize", String.valueOf(chunkSize));
        if (mtime > 0) {
            meta.setProperty("mtime", String.valueOf(mtime));
        }
        try (OutputStream out = Files.newOutputStream(metaFile)) {
            meta.store(out, null);
        }
        sendJson(ex, 200, statusJson(key, staging, meta));
    }

    private void chunk(HttpExchange ex, Path staging, Map<String, String> q) throws IOException {
        if (!"PUT".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            return;
        }
        Properties meta = loadMeta(staging);
        if (meta == null) {
            sendJson(ex, 404, "{\"error\":\"unknown transfer\"}");
            return;
        }
        long size = Long.parseLong(meta.getProperty("size"));
        long chunkSize = Long.parseLong(meta.getProperty("chunkSize"));
        int chunkCount = chunkCount(size, chunkSize);
        int index;
        try {
            index = Integer.parseInt(q.getOrDefault("index", ""));
        } catch (NumberFormatException nfe) {
            sendJson(ex, 400, "{\"error\":\"bad index\"}");
            return;
        }
        if (index < 0 || index >= chunkCount) {
            sendJson(ex, 400, "{\"error\":\"index out of range\"}");
            return;
        }
        long expected = index == chunkCount - 1 && size % chunkSize != 0 ? size % chunkSize : Math.min(chunkSize, size);
        String sha256 = q.get("sha256");
        if (sha256 != null && !sha256.matches("[a-f0-9]{64}")) {
            sendJson(ex, 400, "{\"error\":\"bad sha256\"}");
            return;
        }
        Path tmp = staging.resolve(index + ".tmp");
        long written;
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        try (InputStream in = ex.getRequestBody();
                OutputStream out = new java.security.DigestOutputStream(Files.newOutputStream(tmp), digest)) {
            written = in.transferTo(out);
        }
        if (written != expected) {
            Files.deleteIfExists(tmp);
            sendJson(ex, 400, "{\"error\":\"chunk size mismatch\"}");
            return;
        }
        // Optional end-to-end integrity: reject a chunk whose bytes do not
        // match the digest the client computed before sending. 422 is
        // retryable — the client re-sends the same chunk.
        if (sha256 != null) {
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            if (!hex.toString().equals(sha256)) {
                Files.deleteIfExists(tmp);
                sendJson(ex, 422, "{\"error\":\"digest mismatch\"}");
                return;
            }
        }
        Files.move(tmp, staging.resolve(index + ".chunk"),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        ex.sendResponseHeaders(204, -1);
    }

    private void status(HttpExchange ex, String key, Path staging) throws IOException {
        Properties meta = loadMeta(staging);
        if (meta == null) {
            sendJson(ex, 404, "{\"error\":\"unknown transfer\"}");
            return;
        }
        sendJson(ex, 200, statusJson(key, staging, meta));
    }

    private void complete(HttpExchange ex, Path staging, Devices.Device device, Path deviceRoot)
            throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            return;
        }
        Properties meta = loadMeta(staging);
        if (meta == null) {
            sendJson(ex, 404, "{\"error\":\"unknown transfer\"}");
            return;
        }
        String name = meta.getProperty("name");
        Path root = startRoot(meta, deviceRoot);
        SpecialPaths.Where where = special.locate(ex, device, root, name);
        if (where == null || where.target().equals(where.base())
                || !SpecialPaths.writable(ex, where)
                || (!where.safe() && (!device.canWriteSub(Devices.Device.firstSegment(root, where.target()))
                        || offLimits(device, root, name)))) {
            sendJson(ex, 404, "{\"error\":\"folder not found\"}");
            return;
        }
        // Relative to the base it lands in: the device root, or the safe folder.
        Path base = where.base();
        String relName = where.safe() ? name.split("/", 2)[1] : name;
        long size = Long.parseLong(meta.getProperty("size"));
        long chunkSize = Long.parseLong(meta.getProperty("chunkSize"));
        int chunkCount = chunkCount(size, chunkSize);
        for (int i = 0; i < chunkCount; i++) {
            if (!Files.exists(staging.resolve(i + ".chunk"))) {
                sendJson(ex, 409, "{\"error\":\"missing chunk\",\"index\":" + i + "}");
                return;
            }
        }
        Path target = uniqueTarget(base, relName);
        Path assembling = staging.resolve("assembling");
        try (OutputStream out = Files.newOutputStream(assembling)) {
            for (int i = 0; i < chunkCount; i++) {
                try (InputStream in = Files.newInputStream(staging.resolve(i + ".chunk"))) {
                    in.transferTo(out);
                }
            }
        }
        if (Files.size(assembling) != size) {
            Files.deleteIfExists(assembling);
            sendJson(ex, 500, "{\"error\":\"assembled size mismatch\"}");
            return;
        }
        try {
            Files.move(assembling, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // Staging is on another drive: copy next to the target, then
            // rename there so the finished file still appears atomically.
            Path copy = target.resolveSibling("." + target.getFileName() + "." + key(staging));
            Files.copy(assembling, copy, StandardCopyOption.REPLACE_EXISTING);
            Files.move(copy, target, StandardCopyOption.ATOMIC_MOVE);
            Files.delete(assembling);
        }
        // Keep the file's original timestamp, not the assembly time.
        String mtime = meta.getProperty("mtime");
        if (mtime != null) {
            try {
                Files.setLastModifiedTime(target,
                        java.nio.file.attribute.FileTime.fromMillis(Long.parseLong(mtime)));
            } catch (Exception ignored) {
                // best effort: some filesystems refuse
            }
        }
        deleteRecursively(staging);
        String rel = (where.safe() ? SpecialPaths.SAFE + "/" : "")
                + base.relativize(target).toString().replace('\\', '/');
        sendJson(ex, 200, "{\"name\":" + FilesHandler.quote(rel) + "}");
    }

    /** Cancels a transfer: drops its staging area entirely. Idempotent. */
    private void abort(HttpExchange ex, Path staging) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            return;
        }
        deleteRecursively(staging);
        ex.sendResponseHeaders(204, -1);
    }

    /* ---------- helpers ---------- */

    private Properties loadMeta(Path staging) throws IOException {
        Path metaFile = staging.resolve("meta.properties");
        if (!Files.exists(metaFile)) {
            return null;
        }
        Properties meta = new Properties();
        try (InputStream in = Files.newInputStream(metaFile)) {
            meta.load(in);
        }
        return meta;
    }

    private String statusJson(String key, Path staging, Properties meta) throws IOException {
        long size = Long.parseLong(meta.getProperty("size"));
        long chunkSize = Long.parseLong(meta.getProperty("chunkSize"));
        int chunkCount = chunkCount(size, chunkSize);
        List<Integer> have = new ArrayList<>();
        for (int i = 0; i < chunkCount; i++) {
            if (Files.exists(staging.resolve(i + ".chunk"))) {
                have.add(i);
            }
        }
        StringBuilder json = new StringBuilder("{\"key\":\"").append(key)
                .append("\",\"chunkCount\":").append(chunkCount).append(",\"have\":[");
        for (int i = 0; i < have.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(have.get(i));
        }
        return json.append("]}").toString();
    }

    private static String key(Path staging) {
        return staging.getFileName().toString();
    }

    /** The working folder recorded when the transfer started, or the current one. */
    private Path startRoot(Properties meta, Path deviceRoot) {
        String recorded = meta.getProperty("root");
        if (recorded == null) {
            return deviceRoot;
        }
        Path root = Path.of(recorded).normalize();
        return root.startsWith(fileRoot) ? root : deviceRoot;
    }

    /** Whether the safe relative path lands in a {@link SpecialPaths} location. */
    private boolean offLimits(Devices.Device device, Path deviceRoot, String relPath) throws IOException {
        Path target = deviceRoot.resolve(relPath).normalize();
        return !target.startsWith(deviceRoot)
                || special.forbidden(FolderNames.canonical(deviceRoot, target), device);
    }

    static int chunkCount(long size, long chunkSize) {
        return size == 0 ? 1 : (int) ((size + chunkSize - 1) / chunkSize);
    }

    /** Keep only a safe flat file name: no separators, no traversal, no reserved characters. */
    static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String name = raw.replaceAll("[\\\\/<>:\"|?*\\x00-\\x1f]", "_").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.length() > 255) {
            return null;
        }
        return name;
    }

    /**
     * Sanitizes a relative path (forward-slash separated, as browsers
     * produce for folder uploads): every segment is cleaned like a flat
     * name, traversal is impossible by construction, depth is bounded.
     * Returns the joined safe path, or null when invalid.
     */
    static String sanitizePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] segments = raw.split("/");
        if (segments.length > 32) {
            return null;
        }
        List<String> clean = new ArrayList<>();
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            String name = sanitize(segment);
            if (name == null) {
                return null;
            }
            clean.add(name);
        }
        return clean.isEmpty() ? null : String.join("/", clean);
    }

    /**
     * Resolve the final destination for a (possibly nested) safe relative
     * path, creating parent directories and appending " (n)" before the
     * extension on collision.
     */
    private Path uniqueTarget(Path root, String relPath) throws IOException {
        Path target = root.resolve(relPath).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("unsafe path escaped sanitization: " + relPath);
        }
        // Folders reuse an existing one spelled differently; a file whose
        // name matches an existing entry ignoring case gets a suffix.
        Path parent = FolderNames.canonical(root, target.getParent());
        target = parent.resolve(target.getFileName().toString());
        Files.createDirectories(parent);
        if (!FolderNames.taken(parent, target.getFileName().toString())) {
            return target;
        }
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int n = 1; ; n++) {
            Path candidate = parent.resolve(base + " (" + n + ")" + ext);
            if (!FolderNames.taken(parent, candidate.getFileName().toString())) {
                return candidate;
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }
}
