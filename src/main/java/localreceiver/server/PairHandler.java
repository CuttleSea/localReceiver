package localreceiver.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Pairing and session state. Both endpoints are reachable without a
 * session — they are how a session begins.
 *
 * <ul>
 *   <li>{@code POST /api/pair?code=&name=} — consumes a one-time
 *       pairing code (shown by the host as QR/text); on success sets
 *       the HttpOnly session cookie and returns the device's name and
 *       scope. 403 on an invalid or expired code.</li>
 *   <li>{@code GET /api/session} — what the current requester may do:
 *       {@code {pairingRequired, paired, name, read, write, fileOps,
 *       browse}}. The PWA renders its UI from this.</li>
 * </ul>
 */
public final class PairHandler implements HttpHandler {
    private final Devices devices;
    private final Path fileRoot;
    private final BooleanSupplier pairingRequired;
    private final BooleanSupplier dirBrowse;
    private final java.util.function.Supplier<String> scheme;

    public PairHandler(Devices devices, Path fileRoot, BooleanSupplier pairingRequired,
            BooleanSupplier dirBrowse, java.util.function.Supplier<String> scheme) {
        this.devices = devices;
        this.fileRoot = fileRoot;
        this.pairingRequired = pairingRequired;
        this.dirBrowse = dirBrowse;
        this.scheme = scheme;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/api/pair")) {
                pair(ex);
            } else if (path.equals("/api/session")) {
                session(ex);
            } else {
                ex.sendResponseHeaders(404, -1);
            }
        }
    }

    private void pair(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            return;
        }
        Map<String, String> q = UploadHandler.query(ex);
        Devices.PairOutcome outcome = devices.pair(q.get("code"), q.get("name"), fileRoot);
        if (outcome.error() != null) {
            switch (outcome.error()) {
                case "name" -> UploadHandler.sendJson(ex, 400,
                        "{\"error\":\"device name must be 1-32 of a-z, 0-9, _\"}");
                case "taken" -> UploadHandler.sendJson(ex, 409,
                        "{\"error\":\"that device name is already used\"}");
                default -> UploadHandler.sendJson(ex, 403,
                        "{\"error\":\"invalid or expired pairing code\"}");
            }
            return;
        }
        String token = outcome.token();
        ex.getResponseHeaders().set("Set-Cookie",
                Devices.cookie(token, "https".equals(scheme.get())));
        Devices.Device device = devices.deviceForToken(token);
        UploadHandler.sendJson(ex, 200, "{\"name\":" + FilesHandler.quote(device.name())
                + ",\"path\":" + FilesHandler.quote(device.relPath()) + "}");
    }

    private void session(HttpExchange ex) throws IOException {
        boolean required = pairingRequired.getAsBoolean();
        Devices.Device device = devices.authorize(ex, required);
        StringBuilder json = new StringBuilder("{\"pairingRequired\":").append(required)
                .append(",\"paired\":").append(device != null);
        if (device != null) {
            json.append(",\"name\":").append(FilesHandler.quote(device.name()))
                    .append(",\"path\":").append(FilesHandler.quote(device.relPath()))
                    .append(",\"read\":").append(device.read())
                    .append(",\"write\":").append(device.write())
                    .append(",\"fileOps\":").append(device.write())
                    .append(",\"browse\":").append(dirBrowse.getAsBoolean() && device.browse());
        }
        UploadHandler.sendJson(ex, 200, json.append("}").toString());
    }
}
