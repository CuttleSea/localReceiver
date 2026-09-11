# Claude Security results

Scanned the ttDrop repository (revision `16b36ea20215363287d6db8946bfbb9f407db235` on `main`, clean working tree) in full, unscoped `scan` mode at `medium` effort on 2026-09-10. Four findings survived independent verification: 1 HIGH, 2 MEDIUM, 1 LOW.

## Coverage

The inventory partitioned the repository into three components and every one of the tree's three top-level directories (`.github`, `src`, `tests`) was accounted for — `completenessCheckOutcome` is "checked," so the whole tree is covered, not just the parts a component happened to claim. No component was skipped or dropped.

- **server-and-webapp** — `src/main/java/ttdrop/server`, `src/main/java/ttdrop/util`, `Main.java`, `Config.java`, `src/main/resources/webroot` (the HTTP server, request handlers, and the browser-side webapp).
- **desktop-gui-and-laf** — `src/main/java/ttdrop/gui`, `src/main/java/jacross` and its resources (the Swing desktop GUI and its look-and-feel library).
- **tests-and-ci** — `tests/`, `.github/` (the browser/unit test suites and the release workflow).

The target held 71 tracked files; the inventory asked for about 3 components of roughly 25 files each (cap 24), and dispatched 11 researchers across those cells (one per component × review lens). All 11 returned results, and one verification run of the panel resolved every candidate — no candidate was lost or handed to a further run.

Within the components, researchers report reading 41 of the 63 in-scope files to a conclusion; 20 were explicitly declared not reached (each with a reason: pure look-and-feel/Swing painting code, color/token constants, or static assets grep-checked for I/O and injection sinks but not read line-by-line, plus a handful of intentionally out-of-scope cross-checks). Two files were unaccounted for by any researcher's declaration — `src/main/resources/webroot/icon-512.png` and `icon.svg` — both binary image assets with no code-execution relevance. Eight further files sit outside every component's declared paths; these are root-level and asset files already covered by the component list above, not a gap left by any researcher. No area was deliberately excluded from the scan (no `skippedComponents`), and no finding's reported severity was lowered by the panel.

## Findings

### F1 — Trust-on-first-use CA certificate is distributed with no out-of-band integrity check, enabling MITM during pairing (HIGH, confidence medium)

**Impact.** An attacker positioned on the LAN during a victim's first connection (ARP spoofing, rogue Wi-Fi AP, compromised switch) can intercept the pairing handshake and the plain download of the CA certificate, substituting their own CA. The victim, following the app's own instructions, installs the attacker's CA as a trusted root, after which the attacker can transparently and persistently MITM all of that device's ttDrop traffic — and, because installed CAs are typically trusted system/browser-wide, potentially other HTTPS traffic on that device too — with no further indication to the user.

**Where.** `src/main/java/ttdrop/server/CaCertHandler.java:34` in `handle` (route registered unauthenticated at `src/main/java/ttdrop/server/TtDropServer.java:80`)

**What.** ttDrop's entire HTTPS trust model rests on a device permanently installing the CA certificate served at `GET /ca.crt`. Unlike every other data-handling route, this one is registered without the `devices.authorize(...)` wrapper, and `handle()` performs no check beyond method and file existence before streaming the certificate bytes. Neither the endpoint, `cert-help.html`, the QR code, nor `app.js` ever present a fingerprint or hash of the CA for the user to verify out-of-band — the client simply trusts whatever bytes arrive over the network at first-pairing time.

**Exploit scenario.** A victim starts ttDrop in HTTPS mode on a shared or open Wi-Fi network and scans the printed QR code to pair. An attacker on the same network path answers ARP requests or runs a rogue AP, intercepting the TLS handshake (whose leaf certificate is not yet trusted by anything, so a substituted self-signed certificate looks identical to the browser) and the follow-on `GET /ca.crt`, returning their own CA and leaf certificates instead. The victim installs the attacker's CA per `cert-help.html`'s instructions; the attacker can now decrypt, modify, and inject files as if they were the real ttDrop host.

**Preconditions.**
- Attacker has an active on-path position on the LAN during the victim's very first pairing/CA-install (ARP spoofing, rogue AP, etc.).
- Victim completes the documented cert-install workflow (clicks through any browser warning and installs the fetched CA certificate).

**Fix.** Give the user an out-of-band way to verify the CA before trusting it — display the CA certificate's SHA-256 fingerprint in the desktop GUI (a channel the network attacker does not control), have `cert-help.html`/`app.js` show the same fingerprint for comparison before install, or embed and verify the fingerprint in the QR-code payload itself.

**Verification.** 3/3 lens verifiers confirmed.

### F2 — Hardcoded PKCS12 password protects the CA and server private keys (MEDIUM, confidence medium)

**Impact.** Anyone who can read the keystore files — another local account on a shared machine with a permissive umask, a home-directory backup, a synced dotfile, a container image layer — can trivially decrypt them with the publicly-known constant password and recover the CA private key. With that key they can mint arbitrary server certificates that any device which previously installed this CA will silently trust, enabling an undetectable, persistent MITM against that device's ttDrop (and potentially other HTTPS traffic, since installed CAs are typically trusted system/browser-wide).

**Where.** `src/main/java/ttdrop/server/TlsSupport.java:65` in `sslContext` (constant defined at line 36)

**What.** `STORE_PASS` is the fixed literal `"ttdrop"`, used to encrypt and decrypt every PKCS12 keystore ttDrop creates: `ca.p12` (the CA's RSA private key, which anchors trust for every server certificate the app will ever issue) and `keystore.p12` (the live server private key). The same constant is passed as `-storepass` to every `keytool` invocation that creates these stores. No file-specific permission hardening is applied when the files are written, so the password is the only thing standing between an attacker who reaches the file and the CA private key — and it is identical across every installation, visible in this public repository.

**Exploit scenario.** On a shared or multi-tenant host running ttDrop, a second local user (or an attacker who obtains a copy of the user's home directory, e.g. via a misconfigured backup) reads `~/.config/ttdrop/ca.p12`. They load it with `KeyStore.getInstance("PKCS12")` and the well-known password `"ttdrop"`, extract the CA private key, and issue a certificate for the victim's ttDrop hostname; any device that already trusts the installed ttDrop CA accepts it without warning.

**Preconditions.**
- Attacker already has read access to the user's config directory (local multi-user box, backup exposure, synced dotfiles, etc.) — the password itself provides no protection once the file is exposed.
- A victim device has previously installed the CA certificate from `/ca.crt` (the app's documented normal workflow).

**Fix.** Generate a random per-installation keystore password (stored with restrictive file permissions, or protected via an OS keychain/DPAPI) instead of a fixed literal, and explicitly set POSIX file permissions (e.g. 600) on `ca.p12`, `keystore.p12`, and `ca.crt` when creating them.

**Verification.** 2/3 lens verifiers confirmed (the REACHABILITY lens rated this a false positive, noting the password itself is not attacker-controlled input; IMPACT and DEFENSES confirmed the exposure once the file is read).

### F3 — One-time pairing code exposed via subprocess command-line argument when launching the browser (MEDIUM, confidence medium)

**Impact.** Disclosure of a live bearer credential lets an unrelated local user pair a rogue device to the shared server, gaining whatever read/write access is granted to paired devices.

**Where.** `src/main/java/ttdrop/gui/ServerWindow.java:546` in `openInBrowser` (URL built at `ServerWindow.java:477-478`)

**What.** `rebuildLinks()` embeds the live one-time device-pairing code straight into a URL query string (`?pair=<code>`). That string is then passed as a literal argv element to `xdg-open`/`open`/`rundll32` via `ProcessBuilder` (and, on the primary path, to whatever helper process `java.awt.Desktop.browse()` forks). Command-line arguments of any process are readable by any other local user via `ps` or `/proc/<pid>/cmdline`, and many browsers/`xdg-open` wrappers retain the URL in their own long-lived process argv, so the pairing secret is exposed for as long as that process (or the browser tab it opens) stays alive.

**Exploit scenario.** On a shared or multi-user machine, a second local account runs `ps auxww | grep pair=` (or polls `/proc`) around the time the operator clicks Pair. The `pairCode` captured from the `xdg-open`/browser argv is submitted directly against the ttDrop server's pairing endpoint to register the attacker's own device before the code expires.

**Preconditions.**
- "Require device pairing" is enabled and the server is running.
- The operator clicks the displayed Pair link (or the `Desktop.browse` fallback is used).
- Another local account/process on the same machine inspects the process list within the pairing code's 10-minute validity window.

**Fix.** Never place live secrets in subprocess argv. If a browser must be launched with a pairing token, hand it a short-lived opaque URL that the server resolves server-side, or otherwise avoid exposing the raw code as a command-line argument.

**Verification.** 3/3 lens verifiers confirmed.

### F4 — Pairing credential copied to the shared system clipboard with no protection or expiry (LOW, confidence medium)

**Impact.** Exposure of a live pairing credential to any other process able to read the shared clipboard, enabling unauthorized device pairing.

**Where.** `src/main/java/ttdrop/gui/ServerWindow.java:519` in `linkRow` (URL built at `ServerWindow.java:476-478`)

**What.** The Copy button on the pairing link row places the full pair URL — containing the live one-time `pairCode` minted by `Devices.newPairingCode()` — onto the OS-wide clipboard via `StringSelection`, with no subsequent clearing. Any other local application, clipboard-history utility, or cloud clipboard-sync feature running under the same user session can read this bearer credential for as long as it remains on the clipboard.

**Exploit scenario.** A clipboard-history tool or piece of malware already running under the user's session captures the copied pair URL; the attacker later retrieves the still-valid `pairCode` from that history and uses it to pair an unauthorized device against the shared folder.

**Preconditions.**
- Pairing is enabled and the operator presses Copy on the Pair row.
- Another local process or clipboard-monitoring tool reads the clipboard before it is overwritten.

**Fix.** Avoid putting live authentication tokens on the general clipboard; if kept, clear the clipboard automatically after a short timeout or a single paste, and warn the user that the code is sensitive.

**Verification.** 2/3 lens verifiers confirmed (the REACHABILITY lens rated this a false positive, noting the copy is a deliberate local operator action with no attacker-controlled input; IMPACT and DEFENSES confirmed the exposure to other local readers of the clipboard).

## What was verified

Eleven researchers, one per component × review lens, read the codebase and proposed 7 candidate vulnerabilities; a three-lens adversarial panel (REACHABILITY, IMPACT, DEFENSES) voted on each independently, and one verification run resolved all of them with no candidate lost or left pending. Four survived: F1 and F3 unanimously (3/3), F2 and F4 by majority (2/3, with the dissenting lens judging the input non-attacker-controlled rather than disputing the underlying exposure). No proof-of-concept was run and no exploit was fired — every finding is derived from reading the code, not from a demonstrated exploit. The renderer stamped this report `verified`.

Scans are nondeterministic: running them regularly builds coverage over time. This complements SAST, dependency scanning, and code review; it does not replace them.
