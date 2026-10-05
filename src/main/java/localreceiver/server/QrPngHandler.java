package localreceiver.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import javax.imageio.ImageIO;

import localreceiver.util.QrCode;

/**
 * {@code GET /qr.png} — a QR code PNG of this site's URL, so the page
 * can be handed from one device to another by scanning. By default the
 * encoded text is {@code http://<Host header>/} (the URL the requesting
 * client itself used); {@code ?text=} overrides it (bounded length).
 */
public final class QrPngHandler implements HttpHandler {
    private static final int MAX_TEXT = 80;
    private final java.util.function.Supplier<String> scheme;

    public QrPngHandler(java.util.function.Supplier<String> scheme) {
        this.scheme = scheme;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try (ex) {
            if (!"GET".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            String text = UploadHandler.query(ex).get("text");
            if (text == null) {
                String host = ex.getRequestHeaders().getFirst("Host");
                if (host == null || host.isBlank()) {
                    ex.sendResponseHeaders(400, -1);
                    return;
                }
                text = scheme.get() + "://" + host + "/";
            }
            if (text.length() > MAX_TEXT) {
                ex.sendResponseHeaders(400, -1);
                return;
            }
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(QrCode.toImage(QrCode.encode(text), 8), "png", png);
            byte[] body = png.toByteArray();
            ex.getResponseHeaders().set("Content-Type", "image/png");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
