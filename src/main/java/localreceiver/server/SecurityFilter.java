package localreceiver.server;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Runs in front of every route.
 *
 * <ul>
 *   <li>Refuses cross-site writes: any request other than GET/HEAD whose
 *       {@code Origin} header is not this server's own origin (scheme +
 *       {@code Host}) gets 403, as does {@code Sec-Fetch-Site:
 *       cross-site}. Browsers always send Origin on such requests, so a
 *       page on another site cannot submit forms or fetches here, even in
 *       open mode. Clients that send no Origin (curl, scripts) are not
 *       browsers carrying someone's session and pass.</li>
 *   <li>Refuses unknown host names (DNS rebinding): the {@code Host}
 *       header must be {@code localhost}, this machine's own name
 *       (also as {@code name.local}), or an IP address that is
 *       loopback or on one of this machine's networks. The networks
 *       are worked out automatically from the network interfaces
 *       (address + prefix length) and refreshed every
 *       {@link #NETWORKS_TTL_MS}, so a new Wi-Fi or DHCP lease needs no
 *       configuration. A rebinding page uses its own domain name, which
 *       none of these match.</li>
 *   <li>Forbids framing ({@code X-Frame-Options: DENY} and CSP
 *       {@code frame-ancestors 'none'}) against clickjacking, and sets a
 *       strict default Content-Security-Policy, {@code nosniff} and
 *       {@code Referrer-Policy: no-referrer}. Handlers that set their own
 *       CSP must keep {@code frame-ancestors 'none'} in it.</li>
 * </ul>
 */
final class SecurityFilter extends Filter {
    static final SecurityFilter INSTANCE = new SecurityFilter();

    /** The app shell's policy: only this server's own scripts, styles, images and workers. */
    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self';"
            + " img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self';"
            + " worker-src 'self'; manifest-src 'self'; object-src 'none'; base-uri 'none';"
            + " form-action 'self'; frame-ancestors 'none'";

    private SecurityFilter() {
    }

    @Override
    public void doFilter(HttpExchange ex, Chain chain) throws IOException {
        Headers out = ex.getResponseHeaders();
        out.set("X-Frame-Options", "DENY");
        out.set("Content-Security-Policy", CSP);
        out.set("X-Content-Type-Options", "nosniff");
        out.set("Referrer-Policy", "no-referrer");
        if (!knownHost(ex.getRequestHeaders().getFirst("Host"))) {
            try (ex) {
                sendUnknownHost(ex);
            }
            return;
        }
        if (crossSite(ex)) {
            try (ex) {
                UploadHandler.sendJson(ex, 403, "{\"error\":\"cross-site request refused\"}");
            }
            return;
        }
        chain.doFilter(ex);
    }

    @Override
    public String description() {
        return "Host and Origin checks, anti-framing headers";
    }

    /** A readable page for browsers, JSON for everything else. The host name is never echoed. */
    private static void sendUnknownHost(HttpExchange ex) throws IOException {
        String accept = ex.getRequestHeaders().getFirst("Accept");
        if (accept == null || !accept.contains("text/html")) {
            UploadHandler.sendJson(ex, 421, "{\"error\":\"unknown host name\"}");
            return;
        }
        byte[] body = UNKNOWN_HOST_PAGE.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        // Self-contained: the page's own styles only, nothing else loads.
        ex.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(421, body.length);
        try (var out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static final String UNKNOWN_HOST_PAGE = """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>localReceiver — wrong address</title>
            <style>
            body { font-family: system-ui, sans-serif; max-width: 34rem; margin: 3rem auto;
                   padding: 0 1rem; line-height: 1.5; color: #1f2937; background: #fff; }
            @media (prefers-color-scheme: dark) { body { color: #e5e7eb; background: #111827; } }
            h1 { font-size: 1.4rem; }
            </style>
            </head>
            <body>
            <h1>This address doesn't reach localReceiver</h1>
            <p>localReceiver only answers when it is opened by this computer's
            IP address, its computer name, or <code>localhost</code>. Other
            names are refused to protect your files from websites that try to
            reach your local network.</p>
            <p>Open the address shown in the localReceiver window (or printed on
            its console), for example <code>https://192.168.1.20:4646/</code>.</p>
            </body>
            </html>
            """;

    private static final long NETWORKS_TTL_MS = 30_000;
    private record Network(byte[] address, int prefix) {
    }
    private static volatile List<Network> networks = List.of();
    private static volatile Set<String> names = Set.of();
    private static volatile long refreshed = 0;

    /** Whether a Host header (name or IP, optional port) belongs to this machine or its LAN. */
    static boolean knownHost(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return false;
        }
        String host = hostHeader.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            if (close < 0) {
                return false;
            }
            host = host.substring(1, close);
        } else if (host.indexOf(':') == host.lastIndexOf(':') && host.indexOf(':') >= 0) {
            host = host.substring(0, host.indexOf(':'));
        }
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.equals("localhost") || host.endsWith(".localhost")) {
            return true;
        }
        refresh();
        if (names.contains(host)) {
            return true;
        }
        byte[] ip = ipLiteral(host);
        if (ip == null) {
            return false;
        }
        try {
            InetAddress addr = InetAddress.getByAddress(ip);
            if (addr.isLoopbackAddress()) {
                return true;
            }
        } catch (java.net.UnknownHostException e) {
            return false;
        }
        for (Network n : networks) {
            if (n.address().length == ip.length && samePrefix(n.address(), ip, n.prefix())) {
                return true;
            }
        }
        return false;
    }

    /** The bytes of an IPv4/IPv6 literal, or null for a name (never resolved via DNS). */
    private static byte[] ipLiteral(String host) {
        int zone = host.indexOf('%');
        String bare = zone >= 0 ? host.substring(0, zone) : host;
        boolean v4 = bare.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        boolean v6 = bare.contains(":") && bare.matches("[0-9a-f:.]+");
        if (!v4 && !v6) {
            return null;
        }
        if (v4) {
            for (String octet : bare.split("\\.")) {
                if (Integer.parseInt(octet) > 255) {
                    return null;
                }
            }
        }
        try {
            // A literal never triggers a DNS lookup.
            return InetAddress.getByName(bare).getAddress();
        } catch (java.net.UnknownHostException e) {
            return null;
        }
    }

    private static boolean samePrefix(byte[] a, byte[] b, int prefix) {
        int full = prefix / 8;
        for (int i = 0; i < full; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        int rest = prefix % 8;
        if (rest == 0) {
            return true;
        }
        int mask = 0xff << (8 - rest);
        return (a[full] & mask) == (b[full] & mask);
    }

    /** Re-reads this machine's networks and names, at most every NETWORKS_TTL_MS. */
    private static void refresh() {
        long now = System.currentTimeMillis();
        if (now - refreshed < NETWORKS_TTL_MS) {
            return;
        }
        List<Network> nets = new ArrayList<>();
        Set<String> own = new HashSet<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp()) {
                    continue;
                }
                for (InterfaceAddress ia : nic.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    int prefix = ia.getNetworkPrefixLength();
                    int bits = addr.getAddress().length * 8;
                    // A bogus or missing prefix still admits the address itself.
                    nets.add(new Network(addr.getAddress(),
                            prefix > 0 && prefix <= bits ? prefix : bits));
                }
            }
        } catch (java.net.SocketException ignored) {
            // keep whatever could be read
        }
        try {
            String name = InetAddress.getLocalHost().getHostName().toLowerCase(Locale.ROOT);
            addName(own, name);
        } catch (java.net.UnknownHostException ignored) {
            // no host name: IPs and localhost still work
        }
        String env = System.getenv("COMPUTERNAME");
        if (env == null) {
            env = System.getenv("HOSTNAME");
        }
        if (env != null) {
            addName(own, env.toLowerCase(Locale.ROOT));
        }
        networks = List.copyOf(nets);
        names = Set.copyOf(own);
        refreshed = now;
    }

    private static void addName(Set<String> own, String name) {
        if (name.isBlank() || ipLiteral(name) != null) {
            return;
        }
        own.add(name);
        String shortName = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        own.add(shortName);
        own.add(shortName + ".local");
    }

    /** A state-changing request a browser sent from another origin. */
    static boolean crossSite(HttpExchange ex) {
        String method = ex.getRequestMethod();
        if ("GET".equals(method) || "HEAD".equals(method)) {
            return false;
        }
        Headers in = ex.getRequestHeaders();
        if ("cross-site".equalsIgnoreCase(in.getFirst("Sec-Fetch-Site"))) {
            return true;
        }
        String origin = in.getFirst("Origin");
        if (origin == null) {
            return false;
        }
        String host = in.getFirst("Host");
        String scheme = SpecialPaths.plainHttp(ex) ? "http" : "https";
        return host == null || !origin.toLowerCase(Locale.ROOT)
                .equals(scheme + "://" + host.toLowerCase(Locale.ROOT));
    }
}
