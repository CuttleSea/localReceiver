package localreceiver.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Serves the file area: the working directory the jar was started in.
 *
 * <p>{@code GET /files/<path>} downloads a file; a directory path returns
 * a JSON listing — or, when directory browsing is enabled AND the client
 * is a navigating browser (Accept prefers text/html), a plain HTML index
 * page. Every resolved path must stay inside the file root — traversal
 * outside it is rejected.
 */
public final class FilesHandler implements HttpHandler {
    /**
     * Extensions safe to display inline, mapped to their MIME types.
     * A whitelist on purpose: rendering arbitrary uploaded content
     * (HTML above all) inline on this origin would be stored XSS
     * against the PWA. Everything not listed here downloads as an
     * attachment, and inline responses additionally carry
     * {@code Content-Security-Policy: sandbox} so even scriptable
     * formats (SVG) cannot run code. Text-like formats are served as
     * text/plain deliberately.
     */
    private static final java.util.Map<String, String> VIEWABLE = java.util.Map.ofEntries(
            java.util.Map.entry("png", "image/png"),
            java.util.Map.entry("jpg", "image/jpeg"),
            java.util.Map.entry("jpeg", "image/jpeg"),
            java.util.Map.entry("gif", "image/gif"),
            java.util.Map.entry("webp", "image/webp"),
            java.util.Map.entry("avif", "image/avif"),
            java.util.Map.entry("bmp", "image/bmp"),
            java.util.Map.entry("ico", "image/x-icon"),
            java.util.Map.entry("svg", "image/svg+xml"),
            java.util.Map.entry("pdf", "application/pdf"),
            java.util.Map.entry("mp4", "video/mp4"),
            java.util.Map.entry("m4v", "video/mp4"),
            java.util.Map.entry("webm", "video/webm"),
            java.util.Map.entry("mov", "video/quicktime"),
            java.util.Map.entry("mkv", "video/x-matroska"),
            java.util.Map.entry("mp3", "audio/mpeg"),
            java.util.Map.entry("m4a", "audio/mp4"),
            java.util.Map.entry("wav", "audio/wav"),
            java.util.Map.entry("ogg", "audio/ogg"),
            java.util.Map.entry("flac", "audio/flac"),
            java.util.Map.entry("txt", "text/plain; charset=utf-8"),
            java.util.Map.entry("md", "text/plain; charset=utf-8"),
            java.util.Map.entry("log", "text/plain; charset=utf-8"),
            java.util.Map.entry("csv", "text/plain; charset=utf-8"),
            java.util.Map.entry("json", "text/plain; charset=utf-8"),
            java.util.Map.entry("xml", "text/plain; charset=utf-8"));

    private final Path fileRoot;
    private final java.util.function.BooleanSupplier dirBrowse;
    private final java.util.function.Function<HttpExchange, Devices.Device> auth;

    public FilesHandler(Path fileRoot, java.util.function.BooleanSupplier dirBrowse,
            java.util.function.Function<HttpExchange, Devices.Device> auth) {
        this.fileRoot = fileRoot;
        this.dirBrowse = dirBrowse;
        this.auth = auth;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try (ex) {
            boolean head = "HEAD".equals(ex.getRequestMethod());
            if (!"GET".equals(ex.getRequestMethod()) && !head) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            // Every path is relative to the requesting device's allowed
            // subtree — an unpaired requester has no subtree at all.
            Devices.Device device = auth.apply(ex);
            if (device == null) {
                sendPlain(ex, 401, "Not paired. Open the localReceiver page and enter a pairing code.");
                return;
            }
            if (!device.read()) {
                sendPlain(ex, 403, "Reading is not allowed for this device.");
                return;
            }
            Path root = device.resolveRoot(fileRoot);
            String raw = ex.getRequestURI().getPath().substring("/files/".length());
            String decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
            Path target = root.resolve(decoded).normalize();
            if (!target.startsWith(root)) {
                ex.sendResponseHeaders(403, -1);
                return;
            }
            // Per-subfolder deny list: the top-level subfolder of the
            // device's subtree decides.
            if (!device.canReadSub(Devices.Device.firstSegment(root, target))) {
                ex.sendResponseHeaders(403, -1);
                return;
            }
            if (Files.isDirectory(target)) {
                // The PWA asks for application/json explicitly; a browser
                // navigating to the URL sends Accept: text/html,... — only
                // that case, and only with the toggle on (global AND
                // per-device), gets the index page.
                String accept = ex.getRequestHeaders().getFirst("Accept");
                if (dirBrowse.getAsBoolean() && device.browse()
                        && accept != null && accept.contains("text/html")) {
                    sendHtmlListing(ex, device, root, target);
                } else {
                    sendListing(ex, device, root, target);
                }
            } else if (Files.isRegularFile(target)) {
                sendFile(ex, target, head);
            } else {
                ex.sendResponseHeaders(404, -1);
            }
        }
    }

    private static void sendPlain(HttpExchange ex, int code, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private void sendListing(HttpExchange ex, Devices.Device device, Path root, Path dir)
            throws IOException {
        boolean atDeviceRoot = dir.equals(root);
        // fileOps tells the PWA whether to render management buttons:
        // purely the device's write grant (always-on server-side).
        StringBuilder json = new StringBuilder(
                "{\"fileOps\":" + device.write() + ",\"entries\":[");
        try (Stream<Path> entries = Files.list(dir)) {
            boolean first = true;
            for (Path p : (Iterable<Path>) entries.sorted()::iterator) {
                String entryName = p.getFileName().toString();
                if (entryName.equals(UploadHandler.PART_DIR)
                        || entryName.equals(TrashHandler.DIR)) {
                    continue;
                }
                if (atDeviceRoot && !device.canReadSub(entryName)) {
                    continue;
                }
                if (!first) {
                    json.append(',');
                }
                first = false;
                boolean isDir = Files.isDirectory(p);
                json.append("{\"name\":").append(quote(p.getFileName().toString()))
                        .append(",\"dir\":").append(isDir)
                        .append(",\"size\":").append(isDir ? 0 : Files.size(p))
                        .append(",\"mtime\":").append(Files.getLastModifiedTime(p).toMillis())
                        .append('}');
            }
        }
        json.append("]}");
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * A plain HTML index of the directory for humans browsing /files/
     * directly. Served only when the directory-browse toggle is on. All
     * names are HTML-escaped and hrefs are absolute, percent-encoded
     * paths (so URLs without a trailing slash still work); the page
     * carries a CSP that forbids everything but inline styles, so a
     * hostile file name can never become script on this origin.
     */
    private void sendHtmlListing(HttpExchange ex, Devices.Device device, Path root, Path dir)
            throws IOException {
        boolean atDeviceRoot = dir.equals(root);
        Path rel = root.relativize(dir);
        StringBuilder base = new StringBuilder("/files/");
        for (Path segment : rel) {
            String name = segment.toString();
            if (name.isEmpty()) {
                continue;
            }
            base.append(URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20")).append('/');
        }

        // Styled after the GitHub repository file view: a bordered,
        // rounded list with folder/file icons, folders first, and muted
        // size/age columns on the right. Light/dark via the same
        // palette as the PWA.
        StringBuilder html = new StringBuilder("<!doctype html><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>localReceiver — /" + escapeHtml(rel.toString().replace('\\', '/')) + "</title>"
                + "<style>"
                + ":root{--bg:#fff;--fg:#1f2937;--muted:#6b7280;--accent:#2563eb;"
                + "--surface:#f3f4f6;--border:#d1d5db;--folder:#54aeff}"
                + "@media (prefers-color-scheme:dark){:root{--bg:#111827;--fg:#e5e7eb;"
                + "--muted:#9ca3af;--accent:#60a5fa;--surface:#1f2937;--border:#374151}}"
                + "body{font:14px/1.5 system-ui,-apple-system,'Segoe UI',sans-serif;"
                + "margin:2rem auto;max-width:54rem;padding:0 1rem;background:var(--bg);color:var(--fg)}"
                + "a{color:var(--accent);text-decoration:none}a:hover{text-decoration:underline}"
                + "ul{list-style:none;margin:0.75rem 0;padding:0;border:1px solid var(--border);"
                + "border-radius:6px;overflow:hidden}"
                + "li{display:flex;align-items:center;gap:.6rem;padding:.5rem 1rem;"
                + "border-top:1px solid var(--border)}"
                + "li:first-child{border-top:none}li:hover{background:var(--surface)}"
                + ".icon{display:inline-flex;flex:none;color:var(--muted)}.icon.dir{color:var(--folder)}"
                + "li a{flex:1;color:var(--fg);overflow-wrap:anywhere}li a:hover{color:var(--accent)}"
                + "small{color:var(--muted);white-space:nowrap}"
                + "</style>");

        // Breadcrumbs: localReceiver root, then each ancestor directory.
        html.append("<p><a href=\"/files/\">files</a>");
        StringBuilder crumb = new StringBuilder("/files/");
        for (Path segment : rel) {
            String name = segment.toString();
            if (name.isEmpty()) {
                continue;
            }
            crumb.append(URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20")).append('/');
            html.append(" / <a href=\"").append(crumb).append("\">").append(escapeHtml(name)).append("</a>");
        }
        html.append("</p><ul>");

        try (Stream<Path> entries = Files.list(dir)) {
            for (Path p : (Iterable<Path>) entries
                    .sorted(java.util.Comparator
                            .comparing((Path e) -> !Files.isDirectory(e))
                            .thenComparing(e -> e.getFileName().toString()))::iterator) {
                String name = p.getFileName().toString();
                if (name.equals(UploadHandler.PART_DIR) || name.equals(TrashHandler.DIR)
                        || (atDeviceRoot && !device.canReadSub(name))) {
                    continue;
                }
                boolean isDir = Files.isDirectory(p);
                String href = base + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20")
                        + (isDir ? "/" : "");
                html.append("<li><span class=\"icon").append(isDir ? " dir" : "").append("\">")
                        .append(isDir ? ICON_DIR : ICON_FILE).append("</span>")
                        .append("<a href=\"").append(href).append("\">")
                        .append(escapeHtml(name)).append(isDir ? "/" : "")
                        .append("</a>");
                if (!isDir) {
                    html.append("<small>").append(humanSize(Files.size(p))).append("</small>");
                }
                html.append("<small>").append(humanAge(Files.getLastModifiedTime(p).toMillis()))
                        .append("</small></li>");
            }
        }
        html.append("</ul>");

        byte[] body = html.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        boolean head = "HEAD".equals(ex.getRequestMethod());
        ex.sendResponseHeaders(200, head ? -1 : body.length);
        if (!head) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
    }

    /** Octicon-style 16px inline SVG icons (no external assets, CSP-safe). */
    private static final String ICON_DIR = "<svg aria-hidden=\"true\" viewBox=\"0 0 16 16\""
            + " width=\"16\" height=\"16\" fill=\"currentColor\"><path d=\"M1.75 1A1.75 1.75 0 0 0 0"
            + " 2.75v10.5C0 14.216.784 15 1.75 15h12.5A1.75 1.75 0 0 0 16 13.25v-8.5A1.75 1.75 0 0 0"
            + " 14.25 3H7.5a.25.25 0 0 1-.2-.1l-.9-1.2C6.07 1.26 5.55 1 5 1H1.75Z\"/></svg>";
    private static final String ICON_FILE = "<svg aria-hidden=\"true\" viewBox=\"0 0 16 16\""
            + " width=\"16\" height=\"16\" fill=\"currentColor\"><path d=\"M2 1.75C2 .784 2.784 0"
            + " 3.75 0h6.586c.464 0 .909.184 1.237.513l2.914 2.914c.329.328.513.773.513"
            + " 1.237v9.586A1.75 1.75 0 0 1 13.25 16h-9.5A1.75 1.75 0 0 1 2 14.25Zm1.75-.25a.25.25 0"
            + " 0 0-.25.25v12.5c0 .138.112.25.25.25h9.5a.25.25 0 0 0"
            + " .25-.25V6h-2.75A1.75 1.75 0 0 1 9 4.25V1.5Zm6.75.062V4.25c0"
            + " .138.112.25.25.25h2.688l-.011-.013-2.914-2.914-.013-.011Z\"/></svg>";

    /** GitHub-style relative age, e.g. "29 minutes ago". */
    private static String humanAge(long mtimeMillis) {
        long s = Math.max(0, (System.currentTimeMillis() - mtimeMillis) / 1000);
        if (s < 45) {
            return "just now";
        }
        long minutes = Math.max(1, s / 60);
        if (minutes < 60) {
            return plural(minutes, "minute");
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return plural(hours, "hour");
        }
        long days = hours / 24;
        if (days < 30) {
            return plural(days, "day");
        }
        long months = days / 30;
        if (months < 12) {
            return plural(months, "month");
        }
        return plural(Math.max(1, days / 365), "year");
    }

    private static String plural(long n, String unit) {
        return n + " " + unit + (n == 1 ? "" : "s") + " ago";
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int i = -1;
        while (value >= 1024 && i < units.length - 1) {
            value /= 1024;
            i++;
        }
        return String.format("%.1f %s", value, units[i]);
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /**
     * Sends a file, honoring single-range {@code Range: bytes=a-b} requests
     * with 206 responses so the PWA can download chunks in parallel and
     * resume. The ETag ({@code "size-mtime"}) lets a resuming client detect
     * that the file changed since its partial download was staged.
     */
    private void sendFile(HttpExchange ex, Path file, boolean head) throws IOException {
        long size = Files.size(file);
        String name = file.getFileName().toString();
        String etag = "\"" + size + "-" + Files.getLastModifiedTime(file).toMillis() + "\"";
        ex.getResponseHeaders().set("Accept-Ranges", "bytes");
        ex.getResponseHeaders().set("ETag", etag);
        int dot = name.lastIndexOf('.');
        String viewableType = dot < 0 ? null : VIEWABLE.get(name.substring(dot + 1).toLowerCase());
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        if (viewableType != null) {
            ex.getResponseHeaders().set("Content-Type", viewableType);
            ex.getResponseHeaders().set("Content-Disposition", "inline; filename*=UTF-8''" + encoded);
            ex.getResponseHeaders().set("Content-Security-Policy", "sandbox");
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        } else {
            ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
            ex.getResponseHeaders().set("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
        }

        long from = 0;
        long to = size - 1;
        boolean partial = false;
        String range = ex.getRequestHeaders().getFirst("Range");
        if (range != null && range.startsWith("bytes=") && !range.contains(",")) {
            String spec = range.substring("bytes=".length()).trim();
            int dash = spec.indexOf('-');
            try {
                if (dash > 0) {
                    from = Long.parseLong(spec.substring(0, dash));
                    to = dash < spec.length() - 1 ? Long.parseLong(spec.substring(dash + 1)) : size - 1;
                } else if (dash == 0) {
                    long suffix = Long.parseLong(spec.substring(1));
                    from = Math.max(0, size - suffix);
                }
                partial = true;
            } catch (NumberFormatException nfe) {
                partial = false;
            }
            if (partial && (from > to || from >= size)) {
                ex.getResponseHeaders().set("Content-Range", "bytes */" + size);
                ex.sendResponseHeaders(416, -1);
                return;
            }
            to = Math.min(to, size - 1);
        }

        long length = to - from + 1;
        if (partial) {
            ex.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + to + "/" + size);
        }
        int code = partial ? 206 : 200;
        if (head) {
            ex.getResponseHeaders().set("Content-Length", String.valueOf(length));
            ex.sendResponseHeaders(code, -1);
            return;
        }
        ex.sendResponseHeaders(code, length == 0 ? -1 : length);
        try (var channel = Files.newByteChannel(file); OutputStream out = ex.getResponseBody()) {
            channel.position(from);
            byte[] buf = new byte[64 * 1024];
            long remaining = length;
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(buf);
            while (remaining > 0) {
                bb.clear().limit((int) Math.min(buf.length, remaining));
                int read = channel.read(bb);
                if (read < 0) {
                    break;
                }
                out.write(buf, 0, read);
                remaining -= read;
            }
        }
    }

    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
