package localreceiver.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * TLS for serving over HTTPS on the LAN, built around a reusable
 * per-user Certificate Authority.
 *
 * <p>On the first HTTPS run a CA keypair is generated into
 * {@code ~/.config/localreceiver/ca.p12} with its certificate exported as
 * {@code ca.crt}. The user installs that one certificate on their
 * devices (served at {@code /ca.crt}); from then on every localReceiver
 * server certificate — present and future, regenerated or not — is
 * trusted, which also unlocks service workers and PWA install.
 *
 * <p>Because installing a CA is an irreversible grant of trust, the
 * certificate's SHA-256 fingerprint ({@link #caFingerprint}) is shown
 * in the desktop window (and the headless console) for the user to
 * compare against the device's own certificate details. The CA also
 * carries a critical name-constraints extension ({@link #PERMITTED_IPS},
 * DNS name {@code localhost}), so even a trusted copy can only vouch for
 * local addresses, never for a public website. A CA from before the
 * constraints (v1.0.0) is replaced once on the next HTTPS start; devices
 * must then install the new one.
 *
 * <p>The server certificate ({@code keystore.p12}) is issued by the CA
 * with SANs for {@code localhost}, {@code 127.0.0.1}, and the LAN IPs
 * present at generation time, and a validity Apple accepts (≤825
 * days). Delete {@code keystore.p12} to re-issue (e.g. after an IP
 * change) — the CA, and therefore device trust, persists.
 *
 * <p>All generation happens through the JDK's {@code keytool}
 * (resolved via {@code java.home}). The keystore password is random
 * per installation, kept in {@code keystore.pass} beside the stores.
 * Passwords reach keytool as {@code -storepass:file}, never as a
 * literal argument — a process's argv is readable by any other local
 * user. Every file holding key material is restricted to its owner
 * where the filesystem supports it (POSIX; a no-op on Windows).
 */
public final class TlsSupport {
    private static final String PASS_FILE = "keystore.pass";
    private static final String CA_ALIAS = "localreceiver-ca";
    private static final String SERVER_ALIAS = "localreceiver";

    /**
     * Address ranges the CA may vouch for: loopback, private, link-local
     * and carrier-grade NAT (VPNs such as Tailscale) — every address a
     * LAN server certificate can carry, and no public one.
     */
    static final String[] PERMITTED_IPS = {
        "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
        "169.254.0.0/16", "100.64.0.0/10",
        "::1/128", "fc00::/7", "fe80::/10", "fec0::/10",
    };
    private static final String NAME_CONSTRAINTS_OID = "2.5.29.30";

    private TlsSupport() {
    }

    /** The exported CA certificate (PEM), for the /ca.crt endpoint. */
    public static Path caCertificate(Path configDir) {
        return configDir.resolve("ca.crt");
    }

    /**
     * SHA-256 fingerprint of the CA certificate as upper-case
     * colon-separated hex — the form browsers and OS trust stores show,
     * so the user can compare it against what the device displays
     * before installing. Null when no CA exists yet or it cannot be
     * parsed; recomputed per call so it never goes stale.
     */
    public static String caFingerprint(Path caCert) {
        if (caCert == null || !Files.exists(caCert)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(caCert)) {
            var certificate = CertificateFactory.getInstance("X.509").generateCertificate(in);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            StringBuilder out = new StringBuilder();
            for (byte b : digest) {
                if (!out.isEmpty()) {
                    out.append(':');
                }
                out.append(String.format("%02X", b));
            }
            return out.toString();
        } catch (IOException | java.security.GeneralSecurityException e) {
            return null;
        }
    }

    /** Loads (generating CA and server certificate as needed) an SSLContext. */
    public static SSLContext sslContext(Path configDir) throws IOException {
        Path caStore = configDir.resolve("ca.p12");
        Path caCert = caCertificate(configDir);
        Path keystore = configDir.resolve("keystore.p12");
        Path passFile = storePassFile(configDir, caStore, keystore);
        if (lacksNameConstraints(caCert)) {
            // A pre-constraints CA could vouch for any website: replace it.
            Files.deleteIfExists(caStore);
            Files.deleteIfExists(caCert);
        }
        if (!Files.exists(caStore) || !Files.exists(caCert)) {
            // No CA (first https run, or pre-CA layout): start fresh so
            // the server certificate is always CA-issued.
            Files.deleteIfExists(keystore);
            generateCa(caStore, caCert, passFile);
        }
        if (!Files.exists(keystore)) {
            generateServerCert(configDir, caStore, caCert, keystore, passFile);
        }
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            char[] pass = readPass(passFile);
            try (InputStream in = Files.newInputStream(keystore)) {
                store.load(in, pass);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(store, pass);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(kmf.getKeyManagers(), null, null);
            return context;
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("could not load TLS keystore: " + e.getMessage(), e);
        }
    }

    /**
     * Resolves the file holding the keystore password, creating it on
     * first use with a random 128-bit secret. Stores without a password
     * file (left from ttDrop, which is not supported) cannot be opened,
     * so they are removed and a fresh CA is generated.
     */
    private static synchronized Path storePassFile(Path configDir, Path caStore, Path keystore)
            throws IOException {
        Files.createDirectories(configDir);
        restrictPermissions(configDir, "rwx------");
        Path passFile = configDir.resolve(PASS_FILE);
        if (Files.exists(passFile) && !Files.readString(passFile, StandardCharsets.UTF_8).isBlank()) {
            restrictPermissions(passFile, "rw-------");
            return passFile;
        }
        Files.deleteIfExists(caStore);
        Files.deleteIfExists(caCertificate(configDir));
        Files.deleteIfExists(keystore);
        writePass(passFile, randomPass());
        return passFile;
    }

    private static String randomPass() {
        byte[] secret = new byte[16];
        new SecureRandom().nextBytes(secret);
        StringBuilder hex = new StringBuilder();
        for (byte b : secret) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static void writePass(Path file, String pass) throws IOException {
        Files.writeString(file, pass, StandardCharsets.UTF_8);
        restrictPermissions(file, "rw-------");
    }

    private static char[] readPass(Path passFile) throws IOException {
        return Files.readString(passFile, StandardCharsets.UTF_8).trim().toCharArray();
    }

    /**
     * Best-effort owner-only permissions. POSIX filesystems get the
     * requested mode; Windows (no POSIX view) is left to its own ACLs,
     * per the cross-platform rule that OS-specific hardening is an
     * enhancement, never a requirement.
     */
    private static void restrictPermissions(Path file, String mode) {
        try {
            if (!Files.exists(file)) {
                return;
            }
            PosixFileAttributeView view =
                    Files.getFileAttributeView(file, PosixFileAttributeView.class);
            if (view != null) {
                view.setPermissions(PosixFilePermissions.fromString(mode));
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // best effort: the filesystem refused, keep going
        }
    }

    private static void generateCa(Path caStore, Path caCert, Path passFile) throws IOException {
        Files.createDirectories(caStore.getParent());
        String user = System.getProperty("user.name", "user");
        keytool("-genkeypair",
                "-alias", CA_ALIAS,
                "-keyalg", "RSA", "-keysize", "3072",
                "-validity", "3650",
                "-dname", "CN=localReceiver CA (" + user + ")",
                "-ext", "bc:c=ca:true",
                "-ext", "ku:c=keyCertSign,cRLSign",
                "-ext", NAME_CONSTRAINTS_OID + ":critical=" + nameConstraintsHex(),
                "-storetype", "PKCS12",
                "-keystore", caStore.toString(),
                "-storepass:file", passFile.toString());
        keytool("-exportcert", "-rfc",
                "-alias", CA_ALIAS,
                "-keystore", caStore.toString(),
                "-storepass:file", passFile.toString(),
                "-file", caCert.toString());
        restrictPermissions(caStore, "rw-------");
        restrictPermissions(caCert, "rw-r--r--");
    }

    private static void generateServerCert(Path configDir, Path caStore, Path caCert, Path keystore,
            Path passFile) throws IOException {
        StringBuilder san = new StringBuilder("SAN=dns:localhost,ip:127.0.0.1");
        for (String ip : LocalReceiverServer.lanAddresses()) {
            // Only addresses the CA's name constraints permit; anything
            // else would make clients reject the whole certificate.
            if (permitted(ip)) {
                san.append(",ip:").append(ip);
            }
        }
        Path csr = configDir.resolve("server.csr");
        Path signed = configDir.resolve("server.crt");
        try {
            keytool("-genkeypair",
                    "-alias", SERVER_ALIAS,
                    "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "820",
                    "-dname", "CN=localReceiver",
                    "-storetype", "PKCS12",
                    "-keystore", keystore.toString(),
                    "-storepass:file", passFile.toString());
            keytool("-certreq",
                    "-alias", SERVER_ALIAS,
                    "-keystore", keystore.toString(),
                    "-storepass:file", passFile.toString(),
                    "-file", csr.toString());
            keytool("-gencert",
                    "-alias", CA_ALIAS,
                    "-keystore", caStore.toString(),
                    "-storepass:file", passFile.toString(),
                    "-infile", csr.toString(),
                    "-outfile", signed.toString(),
                    "-validity", "820",
                    "-ext", san.toString(),
                    "-ext", "ku:c=digitalSignature,keyEncipherment",
                    "-ext", "eku=serverAuth");
            keytool("-importcert", "-noprompt",
                    "-alias", CA_ALIAS,
                    "-keystore", keystore.toString(),
                    "-storepass:file", passFile.toString(),
                    "-file", caCert.toString());
            keytool("-importcert", "-noprompt",
                    "-alias", SERVER_ALIAS,
                    "-keystore", keystore.toString(),
                    "-storepass:file", passFile.toString(),
                    "-file", signed.toString());
            restrictPermissions(keystore, "rw-------");
        } finally {
            Files.deleteIfExists(csr);
            Files.deleteIfExists(signed);
        }
    }

    /**
     * True only for a readable CA certificate without the
     * name-constraints extension. An unreadable one is not "lacking":
     * it must fail loudly later rather than be silently replaced.
     */
    static boolean lacksNameConstraints(Path caCert) {
        if (!Files.exists(caCert)) {
            return false;
        }
        try (InputStream in = Files.newInputStream(caCert)) {
            var cert = (java.security.cert.X509Certificate)
                    CertificateFactory.getInstance("X.509").generateCertificate(in);
            return cert.getExtensionValue(NAME_CONSTRAINTS_OID) == null;
        } catch (IOException | java.security.GeneralSecurityException e) {
            return false;
        }
    }

    /** Whether an IP literal falls inside {@link #PERMITTED_IPS}. */
    static boolean permitted(String ip) {
        try {
            byte[] addr = java.net.InetAddress.getByName(ip).getAddress();
            for (String range : PERMITTED_IPS) {
                byte[][] net = cidr(range);
                if (net[0].length != addr.length) {
                    continue;
                }
                boolean inside = true;
                for (int i = 0; i < addr.length && inside; i++) {
                    inside = (addr[i] & net[1][i]) == (net[0][i] & net[1][i]);
                }
                if (inside) {
                    return true;
                }
            }
        } catch (java.net.UnknownHostException e) {
            return false;
        }
        return false;
    }

    /** A CIDR range as {address, mask} bytes. */
    private static byte[][] cidr(String range) throws java.net.UnknownHostException {
        int slash = range.indexOf('/');
        byte[] addr = java.net.InetAddress.getByName(range.substring(0, slash)).getAddress();
        int prefix = Integer.parseInt(range.substring(slash + 1));
        byte[] mask = new byte[addr.length];
        for (int i = 0; i < mask.length; i++) {
            int bits = Math.max(0, Math.min(8, prefix - 8 * i));
            mask[i] = (byte) (bits == 0 ? 0 : 0xff << (8 - bits));
        }
        return new byte[][] {addr, mask};
    }

    /**
     * DER of NameConstraints with only permittedSubtrees (RFC 5280
     * 4.2.1.10): dNSName {@code localhost} and an iPAddress (address +
     * mask) per {@link #PERMITTED_IPS}, hex-encoded for keytool's
     * {@code -ext OID:critical=hex}.
     */
    static String nameConstraintsHex() {
        java.io.ByteArrayOutputStream subtrees = new java.io.ByteArrayOutputStream();
        try {
            // GeneralSubtree ::= SEQUENCE { base GeneralName } (minimum 0 by default)
            subtrees.write(der(0x30, der(0x82, "localhost".getBytes(StandardCharsets.US_ASCII))));
            for (String range : PERMITTED_IPS) {
                byte[][] net = cidr(range);
                byte[] value = new byte[net[0].length * 2];
                for (int i = 0; i < net[0].length; i++) {
                    value[i] = (byte) (net[0][i] & net[1][i]);
                }
                System.arraycopy(net[1], 0, value, net[0].length, net[1].length);
                subtrees.write(der(0x30, der(0x87, value)));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        byte[] constraints = der(0x30, der(0xA0, subtrees.toByteArray()));
        StringBuilder hex = new StringBuilder();
        for (byte b : constraints) {
            hex.append(String.format("%02X", b));
        }
        return hex.toString();
    }

    /** One DER TLV. */
    private static byte[] der(int tag, byte[] value) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(tag);
        int len = value.length;
        if (len < 0x80) {
            out.write(len);
        } else if (len < 0x100) {
            out.write(0x81);
            out.write(len);
        } else {
            out.write(0x82);
            out.write(len >> 8);
            out.write(len & 0xff);
        }
        out.writeBytes(value);
        return out.toByteArray();
    }

    private static void keytool(String... args) throws IOException {
        Path keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "keytool.exe" : "keytool");
        if (!Files.exists(keytool)) {
            throw new IOException("keytool not found at " + keytool + "; cannot generate TLS certificates");
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(keytool.toString());
        cmd.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            if (process.waitFor() != 0) {
                throw new IOException("keytool " + args[0] + " failed: " + output.trim());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while generating TLS certificates", e);
        }
    }
}
