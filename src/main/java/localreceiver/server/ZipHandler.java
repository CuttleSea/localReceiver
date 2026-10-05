package localreceiver.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * {@code GET /api/zip?path=<dir>} — streams a zip of a directory
 * (recursive; the transfer staging area is skipped). An empty or
 * missing {@code path} zips the whole file root. Read-only, so it is
 * available regardless of the file-management toggle. The archive is
 * produced on the fly with {@code java.util.zip} — nothing is staged
 * on disk and the response is chunked.
 */
public final class ZipHandler implements HttpHandler {
    private final Path fileRoot;
    private final SpecialPaths special;
    private final java.util.function.Function<HttpExchange, Devices.Device> auth;

    public ZipHandler(Path fileRoot, SpecialPaths special,
            java.util.function.Function<HttpExchange, Devices.Device> auth) {
        this.fileRoot = fileRoot;
        this.special = special;
        this.auth = auth;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try (ex) {
            if (!"GET".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            Devices.Device device = auth.apply(ex);
            if (device == null) {
                ex.sendResponseHeaders(401, -1);
                return;
            }
            if (!device.read()) {
                ex.sendResponseHeaders(403, -1);
                return;
            }
            Path root = device.resolveRoot(fileRoot);
            Map<String, String> q = UploadHandler.query(ex);
            String raw = q.getOrDefault("path", "");
            String clean = raw.isBlank() ? "" : UploadHandler.sanitizePath(raw);
            SpecialPaths.Where where = clean == null ? null : special.locate(ex, device, root, clean);
            if (where == null) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            Path target = where.target();
            boolean safe = where.safe();
            if (!safe && special.forbidden(target, device)) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            if (!safe && !device.canReadSub(Devices.Device.firstSegment(root, target))) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            if (!Files.isDirectory(target)) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            String name = (target.equals(root) ? "localreceiver" : target.getFileName().toString()) + ".zip";
            ex.getResponseHeaders().set("Content-Type", "application/zip");
            ex.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename*=UTF-8''" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"));
            ex.sendResponseHeaders(200, 0);
            try (ZipOutputStream zip = new ZipOutputStream(ex.getResponseBody());
                    var walk = Files.walk(target)) {
                for (Path file : (Iterable<Path>) walk.sorted()::iterator) {
                    if (!Files.isRegularFile(file)) {
                        continue;
                    }
                    if (!safe && (special.forbidden(file, device)
                            || !device.canReadSub(Devices.Device.firstSegment(root, file)))) {
                        continue;
                    }
                    zip.putNextEntry(new ZipEntry(target.relativize(file).toString().replace('\\', '/')));
                    try (InputStream in = Files.newInputStream(file)) {
                        in.transferTo(zip);
                    }
                    zip.closeEntry();
                }
            }
        }
    }
}
