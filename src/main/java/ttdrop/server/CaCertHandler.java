package ttdrop.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code GET /ca.crt} — the per-user ttDrop CA certificate (PEM) — and
 * {@code GET /ca-fingerprint}, its SHA-256 fingerprint as text.
 * Installing the certificate once on a device makes every ttDrop HTTPS
 * session trusted, unlocking service workers and PWA install. Serving
 * it is safe: it contains only the public half.
 *
 * <p>Installing a CA is an irreversible grant of trust, so the
 * fingerprint is published here for the user to compare against the
 * one the desktop window shows. Both this response and the page asking
 * for the comparison travel the same network an on-path attacker may
 * control, so the fingerprint served here proves nothing on its own —
 * the security comes from the user checking it against the window,
 * which is why the desktop shows it too.
 */
public final class CaCertHandler implements HttpHandler {
    /** Path of the fingerprint route, registered alongside /ca.crt. */
    public static final String FINGERPRINT_PATH = "/ca-fingerprint";

    private final Path caCert;

    public CaCertHandler(Path caCert) {
        this.caCert = caCert;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try (ex) {
            if (!"GET".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            // Contexts match by prefix, so this handler is also offered
            // paths merely starting with its own (/ca-fingerprint.js
            // would otherwise never reach the webroot). Answer only the
            // two exact routes and 404 the rest.
            String path = ex.getRequestURI().getPath();
            boolean wantsFingerprint = FINGERPRINT_PATH.equals(path);
            if (!wantsFingerprint && !"/ca.crt".equals(path)) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            if (!Files.exists(caCert)) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            String fingerprint = TlsSupport.caFingerprint(caCert);
            if (wantsFingerprint) {
                sendFingerprint(ex, fingerprint);
                return;
            }
            byte[] body = Files.readAllBytes(caCert);
            ex.getResponseHeaders().set("Content-Type", "application/x-x509-ca-cert");
            ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=ttdrop-ca.crt");
            if (fingerprint != null) {
                ex.getResponseHeaders().set("X-CA-Fingerprint-SHA256", fingerprint);
            }
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private static void sendFingerprint(HttpExchange ex, String fingerprint) throws IOException {
        if (fingerprint == null) {
            ex.sendResponseHeaders(404, -1);
            return;
        }
        byte[] body = fingerprint.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }
}
