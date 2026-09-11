package ttdrop.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * {@code ~/.config/ttdrop/ca.p12} with its certificate exported as
 * {@code ca.crt}. The user installs that one certificate on their
 * devices (served at {@code /ca.crt}); from then on every ttDrop
 * server certificate — present and future, regenerated or not — is
 * trusted, which also unlocks service workers and PWA install.
 *
 * <p>Because installing a CA is an irreversible grant of trust, the
 * certificate's SHA-256 fingerprint ({@link #caFingerprint}) is shown
 * in the desktop window and served at {@code /ca-fingerprint} so the
 * user can compare the two before trusting it. The window is a channel
 * an on-path network attacker does not control, so a substituted CA
 * shows a mismatching fingerprint.
 *
 * <p>The server certificate ({@code keystore.p12}) is issued by the CA
 * with SANs for {@code localhost}, {@code 127.0.0.1}, and the LAN IPs
 * present at generation time, and a validity Apple accepts (≤825
 * days). Delete {@code keystore.p12} to re-issue (e.g. after an IP
 * change) — the CA, and therefore device trust, persists.
 *
 * <p>All generation happens through the JDK's {@code keytool}
 * (resolved via {@code java.home}). The keystore password is random
 * per installation, kept in {@code keystore.pass} beside the stores;
 * installations predating it are re-encrypted on the next HTTPS start.
 * Passwords reach keytool as {@code -storepass:file}, never as a
 * literal argument — a process's argv is readable by any other local
 * user. Every file holding key material is restricted to its owner
 * where the filesystem supports it (POSIX; a no-op on Windows).
 */
public final class TlsSupport {
    /** Pre-v0.24 fixed password; kept only to migrate older keystores. */
    private static final String LEGACY_PASS = "ttdrop";
    private static final String PASS_FILE = "keystore.pass";
    private static final String CA_ALIAS = "ttdrop-ca";
    private static final String SERVER_ALIAS = "ttdrop";

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
     * first use with a random 128-bit secret.
     *
     * <p>Installations predating the random password hold stores
     * encrypted with {@link #LEGACY_PASS}. Those are re-encrypted in
     * place before the new password is recorded, so a failure at any
     * point leaves the old, working password in force rather than
     * stranding the CA — losing it would invalidate the trust every
     * paired device has already installed.
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
        Path fresh = configDir.resolve(PASS_FILE + ".new");
        writePass(fresh, randomPass());
        Path legacy = configDir.resolve(PASS_FILE + ".legacy");
        try {
            List<Path> existing = new ArrayList<>();
            for (Path store : List.of(caStore, keystore)) {
                if (Files.exists(store)) {
                    existing.add(store);
                }
            }
            if (!existing.isEmpty()) {
                writePass(legacy, LEGACY_PASS);
                for (Path store : existing) {
                    if (!changeStorePass(store, legacy, fresh)) {
                        // Already migrated with the record lost, or an
                        // unreadable store: keep what works today.
                        writePass(passFile, LEGACY_PASS);
                        return passFile;
                    }
                }
            }
            Files.move(fresh, passFile, StandardCopyOption.REPLACE_EXISTING);
            restrictPermissions(passFile, "rw-------");
            restrictPermissions(caStore, "rw-------");
            restrictPermissions(keystore, "rw-------");
            return passFile;
        } finally {
            Files.deleteIfExists(fresh);
            Files.deleteIfExists(legacy);
        }
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

    /** {@code keytool -storepasswd}; false when the store could not be re-encrypted. */
    private static boolean changeStorePass(Path store, Path oldPass, Path newPass) {
        try {
            keytool("-storepasswd",
                    "-keystore", store.toString(),
                    "-storepass:file", oldPass.toString(),
                    "-new:file", newPass.toString());
            return true;
        } catch (IOException e) {
            return false;
        }
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
                "-dname", "CN=ttDrop CA (" + user + ")",
                "-ext", "bc:c=ca:true",
                "-ext", "ku:c=keyCertSign,cRLSign",
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
        for (String ip : TtDropServer.lanAddresses()) {
            san.append(",ip:").append(ip);
        }
        Path csr = configDir.resolve("server.csr");
        Path signed = configDir.resolve("server.crt");
        try {
            keytool("-genkeypair",
                    "-alias", SERVER_ALIAS,
                    "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "820",
                    "-dname", "CN=ttDrop",
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
