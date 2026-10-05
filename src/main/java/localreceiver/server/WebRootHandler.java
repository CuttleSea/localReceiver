package localreceiver.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

/**
 * Serves the PWA from the {@code /webroot} resources embedded in the jar.
 *
 * <p>The webroot deliberately does not exist on disk: every asset is
 * loaded from the classpath on demand, so the jar is self-contained.
 */
public final class WebRootHandler implements HttpHandler {
    private static final Map<String, String> MIME = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("webmanifest", "application/manifest+json; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("ico", "image/x-icon"));

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try (ex) {
            if (!"GET".equals(ex.getRequestMethod()) && !"HEAD".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            String path = ex.getRequestURI().getPath();
            if (path.equals("/")) {
                path = "/index.html";
            }
            // Resource names may not traverse or hide relative segments.
            if (path.contains("..") || path.contains("//")) {
                ex.sendResponseHeaders(400, -1);
                return;
            }
            try (InputStream in = WebRootHandler.class.getResourceAsStream("/webroot" + path)) {
                if (in == null) {
                    ex.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] body = in.readAllBytes();
                ex.getResponseHeaders().set("Content-Type", mimeFor(path));
                ex.getResponseHeaders().set("Cache-Control", "no-cache");
                if ("HEAD".equals(ex.getRequestMethod())) {
                    ex.sendResponseHeaders(200, -1);
                    return;
                }
                ex.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(body);
                }
            }
        }
    }

    private static String mimeFor(String path) {
        int dot = path.lastIndexOf('.');
        String ext = dot < 0 ? "" : path.substring(dot + 1).toLowerCase();
        return MIME.getOrDefault(ext, "application/octet-stream");
    }
}
