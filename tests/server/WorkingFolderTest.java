import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import localreceiver.server.Devices;
import localreceiver.server.LocalReceiverServer;

/**
 * The host changing a device's working folder takes effect once the
 * current action has finished: an upload started in folder A completes
 * in A even when the folder is switched to B before its last request,
 * and the next upload lands in B; a chunked download (one transfer id)
 * keeps reading A until all its bytes went out. Also covers If-Range. Runs an in-process HTTPS server, so
 * it insists on throwaway config and data dirs. Run:
 * LOCALRECEIVER_CONFIG_DIR=$(mktemp -d) LOCALRECEIVER_DATA_DIR=$(mktemp -d) \
 *   pixi run java -Djdk.internal.httpclient.disableHostnameVerification=true \
 *   -cp dist/localreceiver.jar tests/server/WorkingFolderTest.java
 */
public final class WorkingFolderTest {
    static int pass = 0;
    static int fail = 0;

    public static void main(String[] args) throws Exception {
        if (System.getenv("LOCALRECEIVER_CONFIG_DIR") == null
                || System.getenv("LOCALRECEIVER_DATA_DIR") == null) {
            System.err.println("set LOCALRECEIVER_CONFIG_DIR and LOCALRECEIVER_DATA_DIR to temp dirs");
            System.exit(2);
        }
        Path root = Files.createTempDirectory("workingfolder-root");
        Files.createDirectories(root.resolve("A"));
        Files.createDirectories(root.resolve("B"));
        LocalReceiverServer server = new LocalReceiverServer(root);
        server.setPairingRequired(true);
        server.start(0, true);
        Devices devices = server.devices();
        String token = devices.pair(devices.newPairingCode(), "wf_dev", root).token();
        String id = devices.deviceForToken(token).id();
        String base = "https://localhost:" + server.getPort();
        HttpClient client = HttpClient.newBuilder().sslContext(trustAll()).build();
        try {
            devices.update(devices.get(id).withWrite(true).withRelPath("A"));
            String key = "ab".repeat(8);
            check("init in A", post(client, base, token,
                "/api/upload/init?key=" + key + "&path=note.txt&size=2&chunkSize=2") == 200);
            check("chunk accepted", client.send(HttpRequest.newBuilder(
                    URI.create(base + "/api/upload/chunk?key=" + key + "&index=0"))
                .header("Cookie", "localreceiver=" + token)
                .PUT(HttpRequest.BodyPublishers.ofString("ok")).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode() / 100 == 2);

            devices.update(devices.get(id).withRelPath("B"));
            check("complete after the switch", post(client, base, token,
                "/api/upload/complete?key=" + key) == 200);
            check("the started upload finished in A", Files.exists(root.resolve("A/note.txt")));
            check("and not in B", !Files.exists(root.resolve("B/note.txt")));

            String key2 = "cd".repeat(8);
            post(client, base, token, "/api/upload/init?key=" + key2 + "&path=next.txt&size=2&chunkSize=2");
            client.send(HttpRequest.newBuilder(
                    URI.create(base + "/api/upload/chunk?key=" + key2 + "&index=0"))
                .header("Cookie", "localreceiver=" + token)
                .PUT(HttpRequest.BodyPublishers.ofString("ok")).build(),
                HttpResponse.BodyHandlers.discarding());
            post(client, base, token, "/api/upload/complete?key=" + key2);
            check("the next upload lands in B", Files.exists(root.resolve("B/next.txt")));
            check("the device folder is under the id, not the working folder",
                Files.isDirectory(Path.of(System.getenv("LOCALRECEIVER_DATA_DIR")).resolve(id)));

            // Downloads: the same relative file in A and B, different bytes.
            Files.writeString(root.resolve("A/same.txt"), "AAAAAAAA");
            Files.writeString(root.resolve("B/same.txt"), "BBBBBBBB");
            devices.update(devices.get(id).withRelPath("A"));
            String transfer = "0123456789abcdef".repeat(2);
            check("first chunk comes from A",
                range(client, base, token, transfer, 0, 3, null).body().equals("AAAA"));
            devices.update(devices.get(id).withRelPath("B"));
            check("later chunk of the same download still comes from A",
                range(client, base, token, transfer, 4, 5, null).body().equals("AA"));
            check("a request without the transfer id sees B",
                range(client, base, token, null, 0, 3, null).body().equals("BBBB"));
            check("another file is not pinned",
                get(client, base, token, transfer, "/files/next.txt").statusCode() == 200);
            check("last chunk still from A",
                range(client, base, token, transfer, 6, 7, null).body().equals("AA"));
            check("once every byte was served, the pin is gone",
                range(client, base, token, transfer, 0, 3, null).body().equals("BBBB"));

            HttpResponse<String> head = get(client, base, token, null, "/files/same.txt");
            String etag = head.headers().firstValue("ETag").orElse("");
            check("If-Range with the current ETag gets a slice",
                range(client, base, token, null, 0, 1, etag).statusCode() == 206);
            HttpResponse<String> changed = range(client, base, token, null, 0, 1, "\"0-0\"");
            check("If-Range with an old ETag gets the whole file",
                changed.statusCode() == 200 && changed.body().equals("BBBBBBBB"));
        } finally {
            server.stop();
        }
        System.out.println(fail == 0 ? "TEST PASS" : "TEST FAIL");
        System.exit(fail == 0 ? 0 : 1);
    }

    static HttpResponse<String> range(HttpClient client, String base, String token,
            String transfer, int from, int to, String ifRange) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/files/same.txt"))
                .header("Cookie", "localreceiver=" + token)
                .header("Range", "bytes=" + from + "-" + to);
        if (transfer != null) {
            b.header("X-Transfer-Id", transfer);
        }
        if (ifRange != null) {
            b.header("If-Range", ifRange);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> get(HttpClient client, String base, String token,
            String transfer, String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Cookie", "localreceiver=" + token);
        if (transfer != null) {
            b.header("X-Transfer-Id", transfer);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static int post(HttpClient client, String base, String token, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Cookie", "localreceiver=" + token)
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    static SSLContext trustAll() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) { }
            public void checkServerTrusted(X509Certificate[] c, String a) { }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        return context;
    }

    static void check(String label, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("PASS: " + label);
        } else {
            fail++;
            System.out.println("FAIL: " + label);
        }
    }
}
