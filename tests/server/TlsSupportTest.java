import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Base64;

import localreceiver.server.TlsSupport;

/**
 * Headless TLS material test: the per-installation keystore password
 * (random, owner-only; stores without one are replaced), the CA fingerprint published for out-of-band
 * verification, and the CA's name constraints (a CA without them is
 * replaced once). Run:
 * java -cp dist/localreceiver.jar tests/server/TlsSupportTest.java
 */
public final class TlsSupportTest {
    static int pass = 0;
    static int fail = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("localreceiver-tls");
        TlsSupport.sslContext(dir);
        Path caStore = dir.resolve("ca.p12");
        Path keystore = dir.resolve("keystore.p12");
        Path passFile = dir.resolve("keystore.pass");

        check("CA and server keystores generated",
            Files.exists(caStore) && Files.exists(keystore));
        String secret = Files.readString(passFile).trim();
        check("password file written", !secret.isEmpty());
        check("password is 128 random bits of hex", secret.matches("[0-9a-f]{32}"));
        check("the generated password opens the CA store", opens(caStore, secret));
        check("the generated password opens the server keystore", opens(keystore, secret));
        check("key material is owner-only",
            ownerOnly(caStore) && ownerOnly(keystore) && ownerOnly(passFile));

        String fingerprint = TlsSupport.caFingerprint(TlsSupport.caCertificate(dir));
        check("fingerprint is colon-separated SHA-256 hex",
            fingerprint != null && fingerprint.matches("[0-9A-F]{2}(:[0-9A-F]{2}){31}"));
        check("fingerprint matches the certificate actually served",
            fingerprint.equals(derFingerprint(dir.resolve("ca.crt"))));
        check("fingerprint is stable across calls",
            fingerprint.equals(TlsSupport.caFingerprint(TlsSupport.caCertificate(dir))));
        check("no fingerprint without a certificate",
            TlsSupport.caFingerprint(dir.resolve("absent.crt")) == null);

        java.security.cert.X509Certificate ca;
        try (InputStream in = Files.newInputStream(dir.resolve("ca.crt"))) {
            ca = (java.security.cert.X509Certificate)
                java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        check("CA carries critical name constraints",
            ca.getCriticalExtensionOIDs().contains("2.5.29.30"));
        boolean javaParses;
        try {
            byte[] ext = ca.getExtensionValue("2.5.29.30");
            // getExtensionValue wraps the DER in an OCTET STRING: strip it.
            byte[] der = java.util.Arrays.copyOfRange(ext, ext[1] == (byte) 0x81 ? 3 : 2, ext.length);
            new java.security.cert.TrustAnchor(ca, der);
            javaParses = true;
        } catch (IllegalArgumentException e) {
            javaParses = false;
        }
        check("the name constraints parse as valid DER", javaParses);

        // A restart must reuse the CA: regenerating it would silently
        // invalidate the trust every paired device already installed.
        TlsSupport.sslContext(dir);
        check("restart keeps the same CA",
            fingerprint.equals(TlsSupport.caFingerprint(TlsSupport.caCertificate(dir))));
        check("restart keeps the same password", secret.equals(Files.readString(passFile).trim()));

        // Stores without a password file (ttDrop leftovers, unsupported)
        // cannot be opened: they are replaced by a fresh CA.
        Files.delete(passFile);
        TlsSupport.sslContext(dir);
        String fresh = Files.readString(passFile).trim();
        check("a missing password file gets a new random password",
            fresh.matches("[0-9a-f]{32}") && !fresh.equals(secret));
        check("stores without a password file are replaced",
            opens(caStore, fresh) && opens(keystore, fresh)
            && !fingerprint.equals(TlsSupport.caFingerprint(TlsSupport.caCertificate(dir))));

        // A readable CA without name constraints (v1.0.0) is replaced once.
        Path old = Files.createTempDirectory("localreceiver-tls-old");
        keytool("-genkeypair", "-alias", "old-ca", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=old", "-ext", "bc:c=ca:true", "-storetype", "PKCS12",
            "-keystore", old.resolve("old.p12").toString(), "-storepass", "changeit");
        keytool("-exportcert", "-rfc", "-alias", "old-ca",
            "-keystore", old.resolve("old.p12").toString(), "-storepass", "changeit",
            "-file", old.resolve("ca.crt").toString());
        Files.writeString(old.resolve("ca.p12"), "stale");
        String oldPrint = TlsSupport.caFingerprint(old.resolve("ca.crt"));
        TlsSupport.sslContext(old);
        check("a CA without name constraints is replaced",
            !oldPrint.equals(TlsSupport.caFingerprint(old.resolve("ca.crt"))));

        // An unreadable CA store must fail loudly rather than quietly
        // minting a new CA under the devices that trusted the old one.
        Path broken = Files.createTempDirectory("localreceiver-tls-broken");
        Files.writeString(broken.resolve("ca.p12"), "not a keystore");
        Files.writeString(broken.resolve("ca.crt"), "not a certificate");
        // A localReceiver install always has its password file.
        Files.writeString(broken.resolve("keystore.pass"), "0".repeat(32));
        boolean threw = false;
        try {
            TlsSupport.sslContext(broken);
        } catch (java.io.IOException expected) {
            threw = true;
        }
        check("a corrupt CA store is an error, not a silent re-root", threw);
        check("a corrupt certificate has no fingerprint",
            TlsSupport.caFingerprint(broken.resolve("ca.crt")) == null);

        System.out.println(fail == 0 ? "TEST PASS" : "TEST FAIL");
        System.exit(fail == 0 ? 0 : 1);
    }

    /** True when the PKCS12 store opens with this password. */
    static boolean opens(Path store, String password) {
        try (InputStream in = Files.newInputStream(store)) {
            KeyStore.getInstance("PKCS12").load(in, password.toCharArray());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** POSIX rw------- (or a non-POSIX filesystem, where this is moot). */
    static boolean ownerOnly(Path file) throws Exception {
        var view = Files.getFileAttributeView(file,
            java.nio.file.attribute.PosixFileAttributeView.class);
        if (view == null) {
            return true;
        }
        return java.nio.file.attribute.PosixFilePermissions
            .toString(view.readAttributes().permissions()).equals("rw-------");
    }

    /**
     * SHA-256 over the DER bytes decoded straight from the PEM — an
     * independent path to the same value TlsSupport reports.
     */
    static String derFingerprint(Path pem) throws Exception {
        StringBuilder base64 = new StringBuilder();
        for (String line : Files.readAllLines(pem)) {
            if (!line.startsWith("-----")) {
                base64.append(line.trim());
            }
        }
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(Base64.getDecoder().decode(base64.toString()));
        StringBuilder out = new StringBuilder();
        for (byte b : digest) {
            if (!out.isEmpty()) {
                out.append(':');
            }
            out.append(String.format("%02X", b));
        }
        return out.toString();
    }

    static void keytool(String... args) throws Exception {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        cmd.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("keytool " + args[0] + " failed");
        }
    }

    static void check(String label, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("PASS: " + label);
        } else {
            fail++;
            System.out.println("FAIL: " + label);
        }
    }
}
