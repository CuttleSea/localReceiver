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
 * <p>Staging lives in {@code <fileRoot>/.localreceiver-part/<key>/} so partial
 * transfers survive server restarts and stay on the same filesystem as
 * the final destination (atomic finish). The key is a client-derived
 * stable identifier, restricted to lowercase hex.
 */
public final class UploadHandler implements HttpHandler {
    static final String PART_DIR = ".localreceiver-part";
    private static final Pattern KEY = Pattern.compile("[a-f0-9]{8,64}");
    private static final long MAX_CHUNK_SIZE = 64L * 1024 * 1024;

    private final Path fileRoot;
    private final Path partRoot;
    private final java.util.function.Function<HttpExchange, Devices.Device> auth;

    public UploadHandler(Path fileRoot,
            java.util.function.Function<HttpExchange, Devices.Device> auth) {
        this.fileRoot = fileRoot;
        this.partRoot = fileRoot.resolve(PART_DIR);
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
            // Staging is per device (flat "<deviceId>-<key>" dirs), so
            // same-keyed transfers from different devices never collide
            // and one device cannot touch another's staging.
            Path staging = partRoot.resolve(device.id() + "-" + key);
            Path deviceRoot = device.resolveRoot(fileRoot);
            switch (action) {
                case "init" -> init(ex, key, staging, device, q);
                case "chunk" -> chunk(ex, staging, q);
                case "status" -> status(ex, key, staging);
                case "complete" -> complete(ex, staging, device, deviceRoot);
                case "abort" -> abort(ex, staging);
                default -> ex.sendResponseHeaders(404, -1);
            }
        }
    }

    private void init(HttpExchange ex, String key, Path staging, Devices.Device device,
            Map<String, String> q) throws IOException {
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
        if (!device.canWriteSub(topFolder(name))) {
            sendJson(ex, 403, "{\"error\":\"writing to this folder is not allowed\"}");
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
            }
        }
        Files.createDirectories(staging);
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
        if (!device.canWriteSub(topFolder(meta.getProperty("name")))) {
            sendJson(ex, 403, "{\"error\":\"writing to this folder is not allowed\"}");
            return;
        }
        long size = Long.parseLong(meta.getProperty("size"));
        long chunkSize = Long.parseLong(meta.getProperty("chunkSize"));
        int chunkCount = chunkCount(size, chunkSize);
        for (int i = 0; i < chunkCount; i++) {
            if (!Files.exists(staging.resolve(i + ".chunk"))) {
                sendJson(ex, 409, "{\"error\":\"missing chunk\",\"index\":" + i + "}");
                return;
            }
        }
        Path target = uniqueTarget(deviceRoot, meta.getProperty("name"));
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
        Files.move(assembling, target, StandardCopyOption.ATOMIC_MOVE);
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
        String rel = deviceRoot.relativize(target).toString().replace('\\', '/');
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

    /** Top-level folder of a safe relative path, or null for a flat name. */
    private static String topFolder(String relPath) {
        int slash = relPath == null ? -1 : relPath.indexOf('/');
        return slash < 0 ? null : relPath.substring(0, slash);
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
            // The staging and trash areas are never valid client paths.
            if (name == null || name.equals(PART_DIR) || name.equals(TrashHandler.DIR)) {
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
        Path parent = target.getParent();
        Files.createDirectories(parent);
        if (!Files.exists(target)) {
            return target;
        }
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int n = 1; ; n++) {
            Path candidate = parent.resolve(base + " (" + n + ")" + ext);
            if (!Files.exists(candidate)) {
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
