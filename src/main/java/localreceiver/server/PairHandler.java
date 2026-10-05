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
 *   <li>Code guessing is rate limited: after {@value #MAX_FAILURES}
 *       wrong codes from one address (an IPv6 /64 counts as one) within
 *       ten minutes, that address gets 429 until the window ends.</li>
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

    static final int MAX_FAILURES = 5;
    private static final long WINDOW_MS = 10 * 60 * 1000;
    /** Per client address: failures and the start of their window. Shared by both handler instances. */
    private static final Map<String, long[]> FAILURES = new java.util.concurrent.ConcurrentHashMap<>();

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
        String client = clientKey(ex);
        long retryAfter = lockedFor(client);
        if (retryAfter > 0) {
            ex.getResponseHeaders().set("Retry-After", Long.toString(retryAfter));
            UploadHandler.sendJson(ex, 429,
                    "{\"error\":\"too many wrong pairing codes; try again later\"}");
            return;
        }
        Map<String, String> q = UploadHandler.query(ex);
        Devices.PairOutcome outcome = devices.pair(q.get("code"), q.get("name"), fileRoot);
        if ("code".equals(outcome.error())) {
            recordFailure(client);
        }
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

    /** The client address, with IPv6 cut to its /64 so one host cannot rotate through addresses. */
    private static String clientKey(HttpExchange ex) {
        java.net.InetAddress addr = ex.getRemoteAddress().getAddress();
        byte[] bytes = addr.getAddress();
        if (bytes.length == 16) {
            return java.util.HexFormat.of().formatHex(bytes, 0, 8) + "::/64";
        }
        return addr.getHostAddress();
    }

    /** Seconds until the client may try again, or 0 when it is not locked out. */
    private static long lockedFor(String client) {
        long now = System.currentTimeMillis();
        FAILURES.values().removeIf(w -> now - w[1] >= WINDOW_MS);
        long[] window = FAILURES.get(client);
        if (window == null || window[0] < MAX_FAILURES) {
            return 0;
        }
        return Math.max(1, (window[1] + WINDOW_MS - now + 999) / 1000);
    }

    private static void recordFailure(String client) {
        long now = System.currentTimeMillis();
        FAILURES.compute(client, (k, w) -> w == null || now - w[1] >= WINDOW_MS
                ? new long[] {1, now} : new long[] {w[0] + 1, w[1]});
    }

    private void session(HttpExchange ex) throws IOException {
        boolean required = pairingRequired.getAsBoolean();
        Devices.Device device = devices.authorize(ex, required);
        StringBuilder json = new StringBuilder("{\"pairingRequired\":").append(required)
                .append(",\"paired\":").append(device != null);
        if (device != null) {
            // Over plain HTTP open mode can write nowhere (no safe folder).
            boolean write = device.write()
                    && !(SpecialPaths.plainHttp(ex) && device.id().equals(Devices.OPEN.id()));
            json.append(",\"name\":").append(FilesHandler.quote(device.name()))
                    .append(",\"path\":").append(FilesHandler.quote(device.relPath()))
                    .append(",\"read\":").append(device.read())
                    .append(",\"write\":").append(write)
                    .append(",\"fileOps\":").append(write)
                    .append(",\"browse\":").append(dirBrowse.getAsBoolean() && device.browse());
        }
        UploadHandler.sendJson(ex, 200, json.append("}").toString());
    }
}
