# Claude Security results

This is a whole-repository scan of `/mnt/d/git/localReceiver` at commit `53c921cf9733` on `main`, run at `medium` effort on 2026-10-05 (UTC). The working tree was clean. The scan found **9 findings: 8 MEDIUM and 1 LOW**, with no CRITICAL or HIGH. Most of them concern how the per-device folder scopes and deny lists are enforced on a paired device. One concerns the first-pairing CA fingerprint check, which does not do what it claims. F2 and F4 describe the same gap (the `/files/` handler serves the trash and staging folders) from two researchers, so there are eight distinct issues.

## Coverage

The inventory accounted for the whole tree. Every top-level directory (`.github`, `src`, `tests`) was either scanned or skipped on purpose. The scan sized the run at 74 tracked files and asked for about 3 components of roughly 25 files each, with a cap of 24. It examined four components:

- **server**: `src/main/java/localreceiver/server`, `Config.java`, `Main.java`, `src/main/java/localreceiver/util`
- **web-client**: `src/main/resources/webroot`, `src/main/resources/localreceiver`
- **desktop-gui-and-laf**: `src/main/java/localreceiver/gui`, `src/main/java/jacross`, `src/main/resources/jacross/OFL.txt`
- **release-workflow**: `.github`

A breadth sweep also covered the files outside every component (root files such as `README.md`, `pixi.toml`, and the earlier report directory).

**Skipped on purpose:**
- `tests`: "Test code and fixtures, not shipped product".
- `src/main/resources/jacross/NotoSansTC-Regular.ttf`: "Third-party binary font asset".

Each component got one researcher per vulnerability category. All 14 researchers returned. The workflow judged the server, web-client and desktop-gui-and-laf groups to be entirely managed-language code (Java and JavaScript), going by the inventory's language. On that basis, none of the researchers for **server**, **web-client** or **desktop-gui-and-laf** was asked about memory-safety bugs. The release-workflow group kept that category.

**What the researchers report not having read.** In web-client, the researchers searched `style.css` for script-capable constructs but did not read it line by line, and they did not read the two PNG icons (binary images). In desktop-gui-and-laf, they did not read the bundled font (binary). For the authorization category they only grepped `src/main/java/jacross/plaf`, the Swing rendering delegates. Checked against the 46 tracked files inside the components, 43 were read to a conclusion, 3 fall under a declared not-reached path, and none went unaccounted for.

The panel finished in one verification run. No candidate was lost or left unverified, and the panel lowered no severities. No code was executed: no tests, exploits or proofs of concept were run. Every finding comes from reading the source.

## Findings

### F1 — Subfolder deny lists compare path segments case-sensitively, so a paired device can read and write host-denied folders on case-insensitive filesystems (MEDIUM, confidence high)

**Impact.** On Windows and macOS hosts, a paired device fully bypasses the per-subfolder Read and Write restrictions the host configured. It can read, zip-download, upload into, rename, move and trash files in denied folders. Access stays inside the device's own subtree.

**Where.** `src/main/java/localreceiver/server/Devices.java:71` in `Devices.Device.canReadSub` — CWE-178

**What.** The first segment of the client-supplied path (`/files/<path>`, upload `path=`, file-ops `path=`/`to=`, zip `path=`) is checked with an exact, case-sensitive `TreeSet.contains`. The deny-list entries were copied from disk with their exact case. The same path then goes to java.nio, which resolves it under the filesystem's own rules. NTFS and APFS are case-insensitive by default, so `PRIVATE` passes the check and still opens `Private`. Windows trailing-dot stripping, 8.3 short names and macOS NFC/NFD equivalence are the same class of weakness, but the researcher was less certain about those variants.

**Exploit scenario.** The host denies device "kid" Read and Write on `Private`. Kid sends `GET /files/PRIVATE/taxes.pdf`. `firstSegment` returns `PRIVATE`, which is not in `denyRead={"Private"}`, so the file is streamed. `GET /api/zip?path=PRIVATE` downloads the whole folder. `POST /api/upload/init?path=PRIVATE/x.txt` plus chunk/complete writes into the write-denied folder, and `delete`/`move` with `path=PRIVATE/...` moves files out of it.

**Preconditions.**
- The host runs on a case-insensitive filesystem (Windows NTFS or the default macOS APFS/HFS+).
- Pairing mode is on, and the host has unticked Read or Write for a subfolder of that device.
- The attacker holds that device's session.

**Fix.** Stop authorizing by raw string comparison. Canonicalize the target and each denied folder under the device root (`toRealPath()` on the deepest existing ancestor, or `Files.isSameFile`) and compare those. At minimum, compare case-insensitively and NFC-normalized, and reject segments with trailing dots or spaces or containing `~`. Apply the same fix in `canWriteSub` (line 75) and `UploadHandler.topFolder`.

**Verification.** 3/3 lens verifiers confirmed.

### F2 — GET /files/ does not exclude the internal staging and trash directories, exposing other devices' in-flight uploads and trashed files (MEDIUM, confidence high)

**Impact.** A device can read other devices' deleted files and partly uploaded files, bypassing the read deny list for content that passed through staging or the trash. Device ids and transfer keys are also disclosed.

**Where.** `src/main/java/localreceiver/server/FilesHandler.java:96` in `FilesHandler.handle` — CWE-552

**What.** `/files/<path>` is only checked to stay under the device root. `FileOpsHandler.resolveSafe` (lines 157-159) and `ZipHandler` (lines 58-59) reject targets under `.localreceiver-part` and `.localreceiver-trash`, but `FilesHandler` does not. Its filter at lines 146-147 only hides those names from directory listings. This contradicts `TrashHandler`'s documented rule that trash items are visible only to the device that deleted them.

**Exploit scenario.** A device scoped to the whole root, with `Private` read-denied, requests `GET /files/.localreceiver-trash/` to list every device's trash ids. It then downloads `/files/.localreceiver-trash/<id>/item/<name>`, including files another device deleted from `Private`. `GET /files/.localreceiver-part/` lists every `<deviceId>-<key>`, and `/files/.localreceiver-part/<id>-<key>/N.chunk` returns raw chunks of other devices' in-progress uploads.

**Preconditions.**
- Pairing mode is on, and the host has set the device's folder to the whole shared root (`relPath ""`). This is not the default.
- For the deny-list bypass, the host has deny-listed some subfolders for that device.

**Fix.** In `FilesHandler.handle`, reject (403/404) any target under `fileRoot.resolve(UploadHandler.PART_DIR)` or `fileRoot.resolve(TrashHandler.DIR)`, matching `resolveSafe`. A better fix is one shared resolve-and-confine helper in front of every handler. Also make `FileOpsHandler.rename` reject the reserved names.

**Verification.** 3/3 lens verifiers confirmed.

### F3 — A read-denied subfolder can be renamed or moved to a new name, which escapes the name-keyed read deny list (MEDIUM, confidence high)

**Impact.** A device gets full read access to a folder the host explicitly marked unreadable for it. The rename also removes that protection for every other device restricted by the same folder name.

**Where.** `src/main/java/localreceiver/server/FileOpsHandler.java:186` in `rename` — CWE-863

**What.** File operations only check `canWriteSub` on the first segment of the source and destination. The read deny list is keyed by folder name. A device that may write to a read-denied top-level folder can therefore rename it (line 186) or move it (line 145) to a name the list does not cover. `/files/` and `/api/zip` then serve it.

**Exploit scenario.** The host hides `private` from device A (root `""`) by unticking only Read, and Write stays allowed by default. A sends `POST /api/files/rename?path=private&to=loot` and then `GET /api/zip?path=loot`, downloading everything.

**Preconditions.**
- The attacker holds a paired device token with the device-level Write grant.
- The host denied Read but not Write on a top-level subfolder, which the GUI allows.

**Fix.** Require read access on the source for rename, move and delete. Refuse to rename or move any top-level folder that is in `denyRead` or `denyWrite`. Alternatively, make `denyRead` imply `denyWrite`.

**Verification.** 3/3 lens verifiers confirmed.

### F4 — /files/ serves the shared trash and upload staging folders, exposing other devices' deleted files and in-flight uploads and bypassing deny lists (MEDIUM, confidence high)

This is the same root cause as F2, reported by a second researcher and anchored at the deny-list check a few lines below. One fix closes both.

**Impact.** Other devices' deleted files and partial uploads are exposed, including content from folders the reader is denied. This breaks the documented per-device recycle-bin isolation.

**Where.** `src/main/java/localreceiver/server/FilesHandler.java:102` in `handle` — CWE-863

**What.** `/files/` checks only confinement to the device root and the first-segment deny list, and it does not exclude `.localreceiver-trash` or `.localreceiver-part`. Both live under the file root, so the `TrashHandler` ownership check (the device id in `meta.properties`) never runs on this path.

**Exploit scenario.** Device A has root `""` and `denyRead {"bob"}`. Device B is scoped to `bob` and deletes `bob/tax.pdf`. A lists `GET /files/.localreceiver-trash/` with `Accept: application/json`, reads `<id>/meta.properties`, and downloads `<id>/item/tax.pdf`. B's staged uploads under `.localreceiver-part/<Bid>-<key>/` are exposed the same way.

**Preconditions.**
- The attacker's paired device has its folder set to the whole shared root.
- Another device has deleted files or has staged uploads.

**Fix.** Same as F2. Ideally, also move staging and trash outside the served file root.

**Verification.** 3/3 lens verifiers confirmed.

### F5 — CA fingerprint shown to the user is a separate server response, not the fingerprint of the certificate the device installs, so the out-of-band check does not stop CA substitution (MEDIUM, confidence high)

**Impact.** An on-path attacker during first setup can get their own root CA installed permanently on the victim device. They can then intercept that device's HTTPS traffic to any site wherever they are on-path. The real localReceiver CA has no name constraints either, so the claim in `cert-help.html:26-27` that it can only vouch for that computer's own localReceiver servers does not hold. This leaves the CA-substitution concern addressed in commit 19d0aec only partly fixed.

**Where.** `src/main/resources/webroot/cert-check.js:13` in `showCaFingerprint` — CWE-345

**What.** The page shows a "Certificate SHA-256" fetched from `GET /ca-fingerprint` over the same untrusted channel, and it never hashes the `/ca.crt` bytes being installed. `index.html` and `cert-help.html` tell the user to install the certificate if this value matches the host window. An attacker who swaps the `/ca.crt` body but passes `/ca-fingerprint` through unchanged makes the two match.

**Exploit scenario.** The attacker, on-path on the Wi-Fi, terminates TLS for the phone's first visit. The certificate warning looks the same as for the real server, so the user proceeds. The attacker relays everything except `GET /ca.crt`, which it replaces with its own root. The page shows the real fingerprint, which matches the host window, so the user installs and trusts the attacker's CA. In `--http` mode, neither the page nor the host window shows a fingerprint at all.

**Preconditions.**
- The attacker has an on-path LAN position (ARP or DHCP spoofing) during the device's first certificate install.
- The device does not yet trust the localReceiver CA, and the user clicks through the warning.
- The user follows the app's own install guidance.

**Fix.** Do not present any page-rendered value as the verification source, because under MITM the attacker controls the page. Instead, tell users to compare the host window's fingerprint with the one shown by the OS certificate installer or trust-store UI. Remove or caveat the page fingerprint and the `X-CA-Fingerprint-SHA256` header. Add a name-constraints extension to the CA, or stop claiming it is scoped. Do not offer CA install in HTTP mode without an out-of-band fingerprint.

**Verification.** 3/3 lens verifiers confirmed.

### F6 — Pairing toggle updates the old server object after a root change, so the running server stays in open mode while the GUI shows pairing as required (MEDIUM, confidence high)

**Impact.** Pairing is silently off for the rest of the session while the UI says it is enforced. Every unauthenticated LAN client gets full read and write access to the shared root.

**Where.** `src/main/java/localreceiver/gui/ServerWindow.java:140` in the `ServerWindow` constructor (pairingBox action listener) — CWE-863

**What.** The `pairingBox` listener is a lambda in the constructor, so `server` resolves to the constructor parameter, which shadows the field. `chooseRoot()` replaces the field with a new `LocalReceiverServer` (line 377). Later toggles still call `setPairingRequired` on the old, stopped instance, and the live server's `authorize()` keeps its original setting.

**Exploit scenario.** The operator last ran in open mode, so `pairing=false` is persisted. At launch they click "Change…" to pick a new folder, tick "Require device pairing", and start. The window shows a normal "Pair:" link and QR code, but the running server returns `Devices.OPEN` for every request. Any LAN host can list, download, upload, rename, move and delete across the whole root.

**Preconditions.**
- Pairing was unticked (persisted, or set with `--open`) when the operator changed the file root in the same session.
- The operator then ticked "Require device pairing" and ran the server.
- The attacker can reach the server's port.

**Fix.** Make the constructor lambdas reference the field (`this.server...`) in both the `dirBrowseBox` and `pairingBox` listeners, or rename the constructor parameter.

**Verification.** 3/3 lens verifiers confirmed.

### F7 — Open mode authorizes every request, including cross-site ones, with full read/write and no Origin/Host check (CSRF, and DNS rebinding over HTTP) (MEDIUM, confidence medium)

**Impact.** In open mode, a web page any LAN user visits can delete, rename or move files in the share. Under `--http`, it can also read and overwrite the whole share.

**Where.** `src/main/java/localreceiver/server/Devices.java:142` in `authorize` — CWE-352

**What.** With pairing off, `authorize()` returns the full-access `OPEN` device for every request. No handler checks Origin, Referer or Host, and none uses an anti-CSRF token. Writes are plain POSTs with query-string parameters, so a cross-site form can send them. Under `--http`, nothing checks the Host header, so a DNS-rebinding page becomes same-origin and can read every file.

**Exploit scenario.** The host runs `--open`. A LAN user visits an attacker page that auto-submits `POST https://192.168.1.10:4646/api/files/delete?path=Photos`, using the default port and a guessable address. Over HTTPS this works once the browser trusts the app's CA. With `--http`, the page rebinds its own hostname to the LAN IP and reads `GET /api/zip` (the whole share).

**Preconditions.**
- Pairing is disabled (`--open` or the GUI toggle). This is not the default.
- A LAN user visits an attacker-controlled page.
- For HTTPS CSRF, the browser trusts the localReceiver CA. For rebinding reads, the server runs `--http`.
- The browser does not block public-to-private requests.

**Fix.** Reject state-changing requests whose Origin or Referer is not the server's own origin. Reject requests whose Host is not one of the server's own addresses. Alternatively, require a custom header on all API calls so cross-site requests trigger a CORS preflight. Keep this protection active in open mode.

**Verification.** 2/3 lens verifiers confirmed.

### F8 — TLS CA private key, keystore password and device token hashes can be downloaded through /files/ and /api/zip when the shared folder contains the config directory (MEDIUM, confidence medium)

**Impact.** An attacker can take the CA private key and the server TLS key. The CA is an unconstrained trusted root on every device that installed it, so the attacker can intercept those devices' HTTPS traffic to any site. Device session-token hashes in `devices.properties` are exposed as well.

**Where.** `src/main/java/localreceiver/server/FilesHandler.java:397` in `sendFile` — CWE-552

**What.** TLS secrets live in `Config.dir()` (by default `~/.config/localreceiver`: `ca.p12`, `keystore.p12`, `keystore.pass`, `devices.properties`). The shared folder defaults to the working directory, and nothing stops it from containing that directory. `FilesHandler` only enforces root confinement, and `ZipHandler` (lines 77-91) skips only the trash and staging folders.

**Exploit scenario.** The host starts localReceiver from `$HOME` in open mode, or gives a device whole-folder access. A LAN client fetches `GET /files/.config/localreceiver/keystore.pass` and `ca.p12`, or a single `GET /api/zip`. It then opens the CA key and issues trusted certificates for any domain to the devices that installed this CA. The fingerprint check does not help, because the stolen CA is the genuine one.

**Preconditions.**
- The shared folder contains the config directory, for example because the server was started from `$HOME` or with `--root ~`. A desktop double-click launch that starts in the home directory is plausible but was not verified.
- The requester can read the whole share: either open mode, or a paired device the host widened to `""`. Newly paired devices are limited to their own subfolder.
- HTTPS mode has run at least once.

**Fix.** Refuse to serve anything whose real path is inside `Config.dir()`, in `FilesHandler`, `ZipHandler`, `FileOpsHandler` and `UploadHandler`. Refuse to start, or warn, when the file root contains the config directory. Consider denying dot-directories by default, and give the CA name constraints.

**Verification.** 2/3 lens verifiers confirmed.

### F9 — Unauthenticated /api/pair reveals which device names are paired (409 'taken' before the code is checked) (LOW, confidence medium)

**Impact.** Anyone can find out before authenticating which device names (and, by default, folder names) exist. This helps reconnaissance but grants no access.

**Where.** `src/main/java/localreceiver/server/Devices.java:188` in `pair` — CWE-204

**What.** `Devices.pair()` checks whether the supplied name is taken before it validates the pairing code. Any caller can therefore tell from the response whether a name is in use: 409 means taken, 403 means the code was wrong.

**Exploit scenario.** An unpaired client sends `POST /api/pair?code=AAAA-AAAA&name=alice`, then `name=dad_phone`, and so on. Each 409 confirms a device. No code is consumed and there is no rate limit.

**Preconditions.**
- The attacker can reach the server's port.
- Pairing mode is on (the default).

**Fix.** Validate the code first without consuming it, then check the name, then consume the code.

**Verification.** 2/3 lens verifiers confirmed.

## What was verified

First, an inventory partitioned the repository and a threat model framed the attack surface. Then 14 researchers read each component for one vulnerability category each (injection and input handling, authentication and authorization, cryptography and secrets, plus memory and unsafe operations for the release workflow). A breadth sweep covered the files outside the components. Every candidate then went to a three-voter adversarial panel that judged reachability, impact and defenses, and a finding needed 2 of 3 votes to survive. F1 through F6 were confirmed unanimously, and F7, F8 and F9 by 2 of 3. Every researcher returned, and the renderer stamps `verified` (see the revision stamp beside this report). None of this involved running the code. The findings come from reading the source, and no exploit was executed.
