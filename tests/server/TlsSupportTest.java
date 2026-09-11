import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Base64;

import ttdrop.server.TlsSupport;

/**
 * Headless TLS material test: the per-installation keystore password
 * (random, owner-only, migrated off the old fixed literal without
 * disturbing the CA) and the CA fingerprint published for out-of-band
 * verification. Run:
 * java -cp dist/ttdrop.jar tests/server/TlsSupportTest.java
 */
public final class TlsSupportTest {
    static int pass = 0;
    static int fail = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("ttdrop-tls");
        TlsSupport.sslContext(dir);
        Path caStore = dir.resolve("ca.p12");
        Path keystore = dir.resolve("keystore.p12");
        Path passFile = dir.resolve("keystore.pass");

        check("CA and server keystores generated",
            Files.exists(caStore) && Files.exists(keystore));
        String secret = Files.readString(passFile).trim();
        check("password file written", !secret.isEmpty());
        check("password is not the old fixed literal", !secret.equals("ttdrop"));
        check("password is 128 random bits of hex", secret.matches("[0-9a-f]{32}"));
        check("the old fixed password no longer opens the CA store", !opens(caStore, "ttdrop"));
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

        // A restart must reuse the CA: regenerating it would silently
        // invalidate the trust every paired device already installed.
        TlsSupport.sslContext(dir);
        check("restart keeps the same CA",
            fingerprint.equals(TlsSupport.caFingerprint(TlsSupport.caCertificate(dir))));
        check("restart keeps the same password", secret.equals(Files.readString(passFile).trim()));

        // Pre-v0.24 layout: stores encrypted with the fixed password and
        // no password file. Starting must re-encrypt them in place.
        storepasswd(caStore, secret, "ttdrop");
        storepasswd(keystore, secret, "ttdrop");
        Files.delete(passFile);
        TlsSupport.sslContext(dir);
        String migrated = Files.readString(passFile).trim();
        check("legacy install migrates to a random password",
            migrated.matches("[0-9a-f]{32}") && !migrated.equals("ttdrop"));
        check("migrated stores open with the new password",
            opens(caStore, migrated) && opens(keystore, migrated));
        check("migrated stores reject the old fixed password", !opens(caStore, "ttdrop"));
        check("migration leaves the CA — and installed trust — untouched",
            fingerprint.equals(TlsSupport.caFingerprint(TlsSupport.caCertificate(dir))));

        // An unreadable CA store must fail loudly rather than quietly
        // minting a new CA under the devices that trusted the old one.
        Path broken = Files.createTempDirectory("ttdrop-tls-broken");
        Files.writeString(broken.resolve("ca.p12"), "not a keystore");
        Files.writeString(broken.resolve("ca.crt"), "not a certificate");
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

    static void storepasswd(Path store, String oldPass, String newPass) throws Exception {
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        Process process = new ProcessBuilder(keytool.toString(), "-storepasswd",
            "-keystore", store.toString(), "-storepass", oldPass, "-new", newPass)
            .redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("could not stage the legacy keystore");
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
