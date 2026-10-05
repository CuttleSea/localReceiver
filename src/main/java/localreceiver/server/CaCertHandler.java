package localreceiver.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code GET /ca.crt} — the per-user localReceiver CA certificate (PEM).
 * Installing the certificate once on a device makes every localReceiver HTTPS
 * session trusted, unlocking service workers and PWA install. Serving
 * it is safe: it contains only the public half.
 *
 * <p>Installing a CA is an irreversible grant of trust. No fingerprint
 * is served over the network: an on-path attacker controls every
 * response, so it would prove nothing. The user compares the SHA-256
 * the device's own certificate screen shows against the one the host
 * window (or headless console) prints.
 */
public final class CaCertHandler implements HttpHandler {
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
            // paths merely starting with its own; answer only the exact
            // route and 404 the rest.
            String path = ex.getRequestURI().getPath();
            if (!"/ca.crt".equals(path)) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            if (!Files.exists(caCert)) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            byte[] body = Files.readAllBytes(caCert);
            ex.getResponseHeaders().set("Content-Type", "application/x-x509-ca-cert");
            ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=localreceiver-ca.crt");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
