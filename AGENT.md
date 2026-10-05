# AGENT.md — Guide for LLM Agents

This file is the entry point for any LLM agent (Claude, Copilot, Cursor, etc.)
working on this repository. Read it fully before making changes, and keep it
up to date as the project evolves.

## What localReceiver is

**localReceiver** is a local file-transfer tool with two halves:

1. **Server app (Java)** — a desktop application for Windows, macOS, and
   Linux. When started, it acts as:
   - the **PWA host** — serves the sender web app to devices on the network;
   - the **HTTP server** — handles the transfer endpoints;
   - the **receiver host** — receives files sent from the PWA and makes
     files available for download.
2. **PWA (browser)** — a Progressive Web App opened on any device
   (PC, Mac, iPhone, iPad, Android) that acts as the **file sender and
   downloader**, talking to the server over local HTTP.

Typical flow: a user starts the server app on their computer, opens the
served PWA on another device on the same network, and sends files to (or
downloads files from) the server.

### File transfer design

When the user selects files, the PWA **processes them for multithreaded,
continue-able (resumable) transfer** between the PWA and the server. This
is a core design requirement, in both directions (upload and download):

- Files are split into **chunks** so multiple chunks can be transferred
  **concurrently** (parallel requests / workers), not as one monolithic
  stream.
- Transfers must be **resumable**: after an interruption (closed tab,
  dropped Wi-Fi, server restart), a transfer continues from the chunks
  already completed instead of starting over. This implies both sides
  track per-chunk completion state, and chunk integrity is verifiable.
- On the PWA side this must be built from browser-native primitives only
  (e.g. `Blob.slice`, `fetch`, Web Workers, streams) — see the constraints
  below. Chunk size and concurrency should account for memory limits on
  mobile Safari/Android browsers.
- The PWA uses **OPFS (Origin Private File System)** for temporary
  handling: staging chunks, partially downloaded files, and resume state
  live in OPFS rather than in memory, so large files survive page reloads
  and don't exhaust RAM. Prefer `createSyncAccessHandle()` inside Web
  Workers for chunk I/O (the widely supported OPFS write path, including
  Safari); clean up OPFS temporaries once a transfer completes.
- **Upload protocol** (implemented; keep PWA and server in lockstep):
  - `POST /api/upload/init?key=&path=&size=&chunkSize=` → creates or
    finds the staging area; returns `{"key","chunkCount","have":[...]}`
    where `have` lists chunk indexes already stored (resume). `path` is
    a forward-slash relative path for folder uploads (`name` is the
    legacy flat-file spelling); every segment is sanitized, traversal
    rejected, depth capped at 32.
  - `init` also accepts optional `mtime=` (epoch millis): stored in
    the staging meta and re-applied to the assembled file on
    `complete`, so uploads keep their original timestamp. The PWA
    always sends the browser File's lastModified.
  - `PUT /api/upload/chunk?key=&index=n[&sha256=]` (raw body) → stores
    one chunk; written to a temp file then atomically renamed, so
    parallel chunk uploads and crashes are safe. Exact-size check per
    chunk. The optional `sha256` (64 hex chars) is verified against the
    received bytes while writing — mismatch → retryable 422, chunk
    discarded. The PWA sends it whenever `crypto.subtle` exists; absent
    digests are accepted (insecure LAN contexts).
  - `GET /api/upload/status?key=` → same shape as init's response.
  - `POST /api/upload/abort?key=` → cancels a transfer by dropping its
    staging area (idempotent 204).
  - `POST /api/upload/complete?key=` → verifies all chunks, assembles
    into the file root under a collision-safe name (`name (n).ext`),
    deletes staging; returns `{"name":finalName}`. 409 + missing index
    if incomplete.
  - The `key` is a client-derived stable identifier (lowercase hex,
    8–64 chars) hashed from `name|size|lastModified` — it makes resume
    match across page reloads. It is an identifier, not a security
    digest (crypto.subtle is unavailable in insecure LAN contexts).
  - Server staging lives in `<fileRoot>/.localreceiver-part/<key>/` (hidden
    from `/files/` listings) so partial transfers survive server
    restarts and final assembly is an atomic same-filesystem move.
  - PWA side: `uploader.js` Web Worker stages the file into OPFS
    (`localreceiver-outgoing/<key>.bin` + `.json`, sync access handles), then
    uploads missing chunks with a small parallel pool (default 3 × 4 MiB)
    and retry/backoff; `app.js` `resumePending()` rescans OPFS on load
    and resumes unfinished transfers. Where OPFS is unavailable the
    worker falls back to slicing the original `File` (no reload-resume).
  - File names are sanitized server-side (separators/reserved characters
    stripped, no traversal, 255-char cap).
- **QR endpoint** (implemented): `GET /qr.png` returns a QR PNG of
  `http://<Host header>/` (the URL the requesting client used), with a
  `?text=` override capped at 80 chars. Backed by `localreceiver.util.QrCode`,
  a pure-JDK encoder (byte mode, versions 1–5, ECC level M, all eight
  masks) — do not swap it for a library; verify any change with
  `tests/qr/`. The Swing GUI shows the same QR for its address picker.
- **File management** (implemented, ALWAYS ON since v0.22 — access is
  governed entirely by the per-device write grant and subfolder deny
  lists; `--fileops`/`--no-fileops` are accepted-but-ignored):
  `POST /api/files/delete?path=` (moves to the recycle bin — see
  below), `rename?path=&to=` (single sanitized name, same directory,
  409 on collision), `mkdir?path=` (parents created, 409 when
  blocked), and `move?path=&to=` (into target directory, "" = device
  root; " (n)" suffix on conflict; a folder never moves into its own
  subtree). All resolve strictly inside the device subtree — never
  the root itself, `.localreceiver-part`, or `.localreceiver-trash` — via the
  upload sanitizers (`FileOpsHandler`). The `/files/` listing
  advertises `fileOps` = the device's write grant.
- **Recycle bin** (implemented, `TrashHandler`): delete moves entries
  to `<fileRoot>/.localreceiver-trash/<id>/item/<name>` with a sidecar meta
  (original path relative to the file root, deleting device id,
  timestamp) — nothing is destroyed until purged. `GET /api/trash`
  lists ONLY the requesting device's items;
  `POST /api/trash/restore?id=` moves back to the original location
  (recreating folders, " (n)" on conflict, 403 when the original
  location is outside the device subtree or write-denied);
  `POST /api/trash/purge?id=` deletes forever. The trash dir is
  hidden from listings, excluded from zips, and unreachable as a
  client path (sanitizePath rejects `.localreceiver-part`/`.localreceiver-trash`
  segments — this also closes writes into the staging area).
- **Zip downloads** (implemented): `GET /api/zip?path=<dir>` streams a
  recursive zip of a directory (empty path = whole root; staging
  excluded; sanitized/traversal-checked). Read-only, so not gated by
  the file-management toggle (`ZipHandler`).
- **Device pairing and per-device access** (implemented, the default):
  every file endpoint (`/files/*`, `/api/upload/*`, `/api/files/*`,
  `/api/zip`) requires a paired device session — unpaired requesters
  get 401 and the PWA shows only its pairing screen. Only the app
  shell, `/api/pair`, `/api/session`, `/ca.crt`, `/ca-fingerprint`,
  `/cert-help.html`, and `/qr.png` are open. Model (`localreceiver.server.Devices`):
  - Pairing: the host shows a one-time code (GUI: the "Pair" row of
    the links panel — QR of `scheme://host:port/?pair=CODE`, copy
    button, clickable; the code is minted at start and re-minted when
    consumed. Headless prints a code at startup and a fresh one
    whenever one is consumed). Scanning the QR prefills the code — the user must then
    assign the device a **name: 1–32 of `[a-z0-9_]`, unique across
    the device list** (`Devices.NAME`). `POST /api/pair?code=&name=`
    → 400 bad name / 409 taken / 403 bad code; the name is validated
    BEFORE the code is consumed, so rejects never burn a code. On
    success the server sets an HttpOnly session cookie (`localreceiver=`,
    SameSite=Lax, Secure over HTTPS) whose SHA-256 is stored in
    `~/.config/localreceiver/devices.properties`. Codes are in-memory,
    single-use, 10-minute expiry.
  - Renaming (GUI "Rename…", `Devices.rename`): same name rules;
    **the device's folder is never touched** — since v0.23 name and
    folder are decoupled, so a rename moves no files, cannot collide,
    and never strands the other devices pointed at a shared folder.
    Errors: "name", "taken", "unknown". The folder is set at pairing
    and changed only by the host (GUI "Folder…",
    `Device.withRelPath`), which is what lets several devices share one
    browsing root.
  - Per-subfolder access (GUI "Subfolders…" checklist): each device
    carries `denyRead`/`denyWrite` sets of top-level subfolder names
    inside its subtree — a **deny list, so everything (including
    folders created later) is readable and writable by default**.
    Enforced everywhere: listings (JSON and HTML) hide read-denied
    subfolders at the device root, direct reads/zips of them 403, and
    uploads/rename/delete into write-denied subfolders 403
    (`Device.canReadSub`/`canWriteSub` on the first path segment).
  - `GET /api/session` → `{pairingRequired, paired, name, path, read,
    write, fileOps, browse}`; the PWA renders its whole UI from this.
  - Per-device grants, host-edited live in the GUI Devices panel:
    an allowed subtree (`path`, "" = whole root — every handler
    resolves client paths against the device's subtree), `read`
    (listings/downloads/zip), `write` (uploads; AND-ed with the
    global file-management toggle for rename/delete), `browse`
    (AND-ed with the global directory-browse toggle).
  - Isolation default: a new device is scoped to a new folder named
    after it, so devices cannot see the host's root or each other
    until the host widens their path. The name only seeds that folder
    at pairing time — nothing afterwards couples the two, so devices
    may be renamed freely and several may point at one shared folder.
    Upload staging is per device
    (`.localreceiver-part/<deviceId>-<key>/`), so keys never collide across
    devices and no device can touch another's staging.
  - Open mode (`--open` flag / GUI "Require device pairing" off,
    persisted as config `pairing`): every request resolves to the
    virtual OPEN device with full access — the pre-v0.16 behavior.
    The browser test suite runs its shared server this way;
    `pairing.test.mjs` covers the default posture.
  - `LOCALRECEIVER_CONFIG_DIR` overrides the config dir (tests use it to
    avoid touching the real `devices.properties`).
- **Download protocol** (implemented): `/files/<path>` supports `HEAD`
  and single-range `Range: bytes=a-b` GETs (206 + `Content-Range`,
  416 on bad ranges) with an ETag of `"size-mtime"`. A whitelist of
  extensions (`FilesHandler.VIEWABLE`: images, pdf, common video/audio
  containers, text-likes as text/plain) is served `inline` with
  `Content-Security-Policy:
  sandbox` + `nosniff` so direct `/files/` URLs display in the
  browser; every other type — HTML deliberately included — is an
  `attachment` download. Never inline-render non-whitelisted uploads:
  that would be stored XSS on the app origin. The PWA's own file list
  always uses the managed download, never the inline view.
- **Directory browsing** (implemented, default OFF): a `/files/`
  directory URL normally returns the JSON listing
  (`{"fileOps":bool,"entries":[{name,dir,size,mtime}]}`). With the
  GUI's "Allow directory browsing" toggle (live, persisted, config
  `dirBrowse`) or `--browse`, a request whose `Accept` header contains
  `text/html` (a navigating browser) instead gets a server-rendered
  GitHub-repo-style HTML index page (folders first, icons, sizes,
  relative ages, breadcrumbs). The PWA is unaffected either way — it
  requests listings with `Accept: application/json`. The HTML page is
  generated in `FilesHandler.sendHtmlListing`: names HTML-escaped,
  hrefs absolute and percent-encoded, and the page served with
  `Content-Security-Policy: default-src 'none'; style-src
  'unsafe-inline'` + `nosniff` so hostile file names can never become
  script. Keep that CSP and escaping intact in any change. The PWA's
  `downloader.js` worker fetches chunks in parallel with Range requests,
  writes them at their offsets into an OPFS staging file
  (`localreceiver-incoming/<key>.bin` + `.json` tracking completed indexes and
  the ETag), and resumes across reloads; an ETag mismatch on resume
  restarts the transfer. When complete, the main thread hands the staged
  file to the browser as a blob-URL save. **Staging must not be deleted
  at delivery time** — the save streams lazily from the OPFS file — so
  the meta is marked `delivered` and cleanup runs on the next page load.
  Without OPFS, file links fall back to plain navigation downloads.

### HTTPS

The server serves **HTTPS by default** (`--http` or the GUI checkbox
opts out; the choice persists in config). `localreceiver.server.TlsSupport`
builds the material with the JDK's `keytool` (resolved from
`java.home`) in `~/.config/localreceiver/`:

- **Per-user CA, generated on the first HTTPS run and reused for all
  sessions**: `ca.p12` (key) + `ca.crt` (PEM export, public half only).
  Served at `GET /ca.crt` and linked from the PWA footer — installing
  it once on a device makes every localReceiver server certificate trusted,
  present and future, which also unlocks service workers and PWA
  install. Never regenerate the CA implicitly except when `ca.crt`/
  `ca.p12` are missing (that would invalidate installed trust).
- **Server certificate**: `keystore.p12`, issued by the CA with SANs
  for `localhost`, `127.0.0.1`, and the LAN IPs present at generation
  time, `eku=serverAuth`, ≤825-day validity (Apple's trust limit).
  Delete `keystore.p12` to re-issue under the same CA (e.g. after an
  IP change) — installed trust persists. A pre-CA `keystore.p12`
  (missing CA files) is discarded and re-issued.
- **Per-installation keystore password** (`keystore.pass` in the
  config dir, 128 random bits of hex): PKCS12 requires a password, and
  a fixed literal gave anyone who reached the file — a second local
  account, a home-directory backup, a synced dotfile — the CA private
  key, and with it the power to mint certificates every device that
  installed the CA would trust. Installs predating it are re-encrypted
  in place on the next HTTPS start (`keytool -storepasswd`); if that
  migration fails for any reason the old password is kept, because
  losing the CA would invalidate the trust paired devices already
  installed. Passwords reach keytool as `-storepass:file`/`-new:file`,
  never as literal arguments — argv is world-readable.
- **Owner-only key material**: the config dir (700) and `ca.p12`,
  `keystore.p12`, `keystore.pass` (600) are restricted on POSIX
  filesystems, best-effort and a no-op on Windows.
- **CA fingerprint for out-of-band verification**
  (`TlsSupport.caFingerprint`, upper-case colon-separated SHA-256):
  installing a CA is an irreversible grant of trust, and an attacker
  on the network path during first pairing can substitute their own CA
  for the one the device downloads. The desktop window prints the
  fingerprint — a channel that attacker does not control — and the app
  shell and `cert-help.html` print what the device actually received
  (`/ca-fingerprint`, plus an `X-CA-Fingerprint-SHA256` header on
  `/ca.crt`) so the user compares the two before trusting it. Keep
  both halves: the served value proves nothing on its own, the
  comparison against the window is the whole mechanism.

Caveats to preserve in any related change: without installing the CA,
devices tap through the browser interstitial once — a merely-accepted
cert still gives a secure context (OPFS staging and reload-resume
work), but Chromium refuses **service-worker** scripts over it, so the
app must keep registering the SW best-effort and degrading gracefully.
URLs and QR codes must always follow `LocalReceiverServer.scheme()`. The gold
verification for TLS changes: `curl --cacert ~/.config/localreceiver/ca.crt
https://localhost:<port>/` must succeed with no `-k`.

### Server runtime layout

- The server ships as a **GUI jar**. The user **places the jar in the
  target directory and runs it from there** (e.g. `N:\the\target\path` on
  Windows or `/the/target/path` on Unix). That directory is the server's
  **working directory** and is the file root served at
  `https://localhost/files/` — received files land there and files placed
  there are downloadable. Resolve the file root from the actual working
  directory at runtime; never hard-code paths, and handle both Windows
  drive-letter paths and Unix paths.
- File-root override precedence (implemented in `Main`): the
  `--root <dir>` flag, then the persisted GUI chooser selection
  (config `root`), then the working directory. The GUI can change the
  shared folder while stopped and offers a persisted
  "Start on launch" autostart option.
- The **webroot does not exist on disk**. The PWA assets (HTML, JS, CSS,
  manifest, service worker, icons) are embedded in the jar and served by
  the server **on demand** from its resources. Do not scaffold a webroot
  directory next to the jar or read PWA assets from the filesystem.
- **Configuration** lives in `.config/localreceiver/` under the **user home
  directory** (i.e. `~/.config/localreceiver/`, resolved via `user.home` — the
  same layout on Windows, macOS, and Linux). Config never lives in the
  working directory; the working directory is exclusively the file area.
  Implemented as `localreceiver.Config` (`config.properties`; keys: `port`,
  `https` (default true), `root`, `autostart` (default false),
  `dirBrowse` (default false), `pairing` (default true); a legacy
  `fileOps` key is ignored); paired devices live in
  `devices.properties` beside it. `LOCALRECEIVER_CONFIG_DIR` overrides the
  directory. The GUI's shared-folder chooser is bounded to the
  working directory the jar was started from.
- The GUI shows a **links panel** while running: App (`/`), Pair
  (`/?pair=CODE`, only while pairing is required), and Files
  (`/files/`, only while directory browsing is enabled). Every row is
  clickable (opens the default browser via `java.awt.Desktop.browse`,
  falling back to `xdg-open`/`open`/`rundll32`), copyable, and can be
  shown in the shared QR panel via its QR toggle. Two rules protect
  the live pairing code, which is a bearer credential for ten minutes:
  - **Never hand it to a subprocess.** `openInBrowser` runs every URL
    through `ServerWindow.stripPairCode` first, because argv is
    readable by any other local user (`ps`, `/proc/<pid>/cmdline`) and
    browsers keep it in their own long-lived argv. The operator reads
    the code off the window and types it; a dialog says so. The QR
    code still carries it — that is on screen, not in argv.
  - **Clipboard copies expire.** Copying the Pair row wipes the
    clipboard again after `CLIPBOARD_CLEAR_SECONDS`, and only if it
    still holds exactly that text, so clipboard-history tools get a
    short window and the user's later copies are never destroyed.
  Below the links the window prints the **CA fingerprint** while
  serving HTTPS; that display is what makes installing the CA on a
  device safe (see HTTPS).
- **GUI theming — the `jacross` package** (Tier 0 subset of the
  JaCross design system): a token layer (`ColorRole`/`Tokens`/`Themes`)
  with OKLab/CIE-L* tonal palettes (`Ok`, `TonalPalette`),
  `JaCrossLaf extends BasicLookAndFeel` with delegates for the
  controls localReceiver uses (button, combo box; check box via a Path2D
  icon; fields via a shared focus-aware rounded border), and a
  `Platform` probe (OS dark mode via reg/defaults/gsettings, Windows
  accent via desktop property — each optional, 2s-bounded, quiet
  fallback to light + brand seed `0x2563EB`). Policy: FLUENT on
  Windows, MATERIAL elsewhere. The UI font is the embedded
  **Noto Sans TC** Regular — a **TrueType-outline (glyf) static
  instance** (~7.1 MB raw, the dominant jar weight; bold is derived
  synthetically). Never swap in a CFF-outline OTF: it loads with the
  right family name but rasterizes as a fallback face on some JDK
  builds — the L&F test's pixel-comparison checks exist to catch
  exactly that. `jacross.UiScale.autoApply()` (first statement of
  `main`, before any AWT class loads) sets `sun.java2d.uiScale` from
  GNOME's scaling settings on Linux, never overriding an explicit
  property or GDK_SCALE. `Main` calls
  `JaCross.detect()` off the EDT then `JaCross.apply()` on it, GUI
  path only — headless mode never touches Swing or the font. Painters
  read tokens via `UIManager.get("jacross.tokens")` at paint time;
  UI-delegate classes must be referenced by class literal in
  `JaCrossLaf` (string names alone would be dropped by the
  reachability-based build). Verify with `tests/laf/`.
- Keep the served file area and the transfer temporaries distinct from
  config, and make sure serving `/files/` never escapes the working
  directory (path traversal — see the security ground rule).

## Hard constraints — never violate these

### PWA
- **Vanilla JavaScript only.** No external JS libraries, no frameworks, no
  bundler-installed dependencies, no CDN scripts. Not even "small" ones.
- **Vanilla CSS only.** No CSS frameworks (no Tailwind, Bootstrap, etc.),
  no preprocessors as a build requirement.
- **Browser-native APIs only** (Fetch, File/Blob, Service Worker, Web
  Manifest, drag-and-drop, streams, etc.).
- Must work across **desktop browsers, iOS/iPadOS Safari, and Android** —
  check cross-browser support (especially Safari/WebKit, which is the usual
  laggard for File System Access, background sync, and streaming APIs)
  before relying on an API, and provide fallbacks where support is partial.
- It must remain installable/usable as a PWA served from the local Java
  server — no reliance on internet-hosted assets.
- **Webroot code style** (`src/main/resources/webroot/`, maintainer
  mandate — different from the Java conventions):
  - Visual design is "clean professional minimalist"; the file lists
    (app and directory pages) are styled after the GitHub repository
    file view (bordered rounded list, folders first, icons, muted
    size/age columns).
  - **No indentation** in HTML, JS, or CSS — every line starts at
    column 0. Break lines so JS and CSS stay **under 100 characters
    per line**.
  - **No comments** in webroot files — they ship to every client;
    document behavior in AGENT.md instead.
  - HTML declares `<meta charset="utf-8">` so emoji and CJK characters
    render correctly.
  - Every served page ends with a **privacy note** stating the
    external JS libraries/frameworks/web fonts/APIs used — which must
    always truthfully read "none" (browser-built-ins excluded).
  - Bump the `CACHE` version in `sw.js` whenever any shell asset
    changes, or installed PWAs keep serving the old shell.

### Server
- **Java**, and it must run on Windows, macOS, and Linux. No OS-specific
  behavior without explicit cross-platform handling (paths, file names,
  line endings, default browsers, firewalls/ports).
- **Pure JDK code, Tier 0+**: no third-party jars — the only non-JDK
  artifact is data, not code: the embedded Noto Sans TC font (SIL OFL,
  `src/main/resources/jacross/`, keep `OFL.txt` beside it). Any
  OS-specific visual (dark mode, accent colour) must be a probed
  enhancement with a pure-Java fallback, never a requirement — the
  JaCross rule of the lowest rung.
- The server serves everything the PWA needs; devices on the LAN must not
  need internet access for localReceiver to work.

### General
- The project is licensed **GPL-3.0**. Do not add code copied from
  incompatibly-licensed sources. This includes icon artwork: the app
  icon is an original drawing (a Font Awesome **Pro** icon was
  offered as reference and rejected — commercial licence, not
  redistributable in an open-source repo). Never embed FA Pro assets;
  only its colour (#003e6f) was adopted.
- **README.md is bilingual**: English (UK spelling) followed by
  Traditional Chinese as used in Taiwan (臺灣正體). Keep both sections
  in sync in the same commit. Use Taiwanese glossary only — e.g.
  伺服器/檔案/資料夾/連接埠/憑證/網路/軟體/預設/支援/透過/遞迴 —
  never mainland-China terms (服务器/文件夹/端口/证书/网络/默认/支持).

If a change you're considering would break one of these constraints, stop
and ask the maintainer instead of proceeding.

## Current state of the repository

Implemented and verified end to end (Playwright/Chromium against the
running jar): the Java server (GUI + headless, embedded webroot,
`/files/` with Range support, chunked upload API with folder paths,
delete/rename API, config persistence, HTTPS-by-default with a
per-user CA, QR endpoints, root chooser/--root/autostart, default-off
file-management and directory-browsing toggles, click-to-open URL) and
the PWA (installable shell, chunked parallel resumable uploads and
downloads staged in OPFS with reload-resume, folder uploads,
cancellation, file management, zip downloads, QR sharing, CA install
link, GitHub-style file lists).

Browser tests live in `tests/browser/`, QR tests in `tests/qr/` (see
Testing). Remaining ideas live under Known gaps.

Do not assume the existence of any module, build file, or directory that
is not present on disk — verify with the actual file tree first, and
keep the sections below current in the same commit as any change.

## Ground rules

1. **Never invent project facts.** If something isn't answered by this
   file, the README, the code, or git history, ask the maintainer rather
   than guessing.
2. **Keep this file current.** Any structural change — build system,
   source layout, test framework, CI, release process, new constraint —
   must update the relevant AGENT.md section in the same commit.
3. **Small, reviewable changes.** Focused commits with clear messages.
4. **No secrets, no build artifacts.** Anything matching `.gitignore`
   patterns must never be force-added.
5. **Security matters even on a LAN.** The server accepts file uploads:
   sanitize file names, prevent path traversal, bound sizes sensibly, and
   never execute received content.

## Git workflow

- The default branch is `main`. Never commit directly to `main` unless the
  maintainer explicitly says so.
- Develop on a feature branch (agents in managed environments are usually
  assigned a `claude/...` branch — use the one you were given).
- Push with `git push -u origin <branch-name>`; open a pull request only
  when asked to.
- Commit messages in the imperative mood ("Add drop handler", not "Added
  drop handler"), short subject line, optional body explaining *why*.
- **Every batch updates `CHANGELOG`** (no file extension): add bullets
  describing the batch under the `## Unreleased` section in the same
  commit. CHANGELOG is the single source of truth for GitHub release
  notes — see Releases.
- **Every batch of modifications ends with a commit — nothing more.**
  Never leave finished work uncommitted, but do not push automatically:
  **push and pull requests happen on demand only**, when the maintainer
  asks for them.
  - Exception: in an ephemeral environment (e.g. a Claude Code cloud
    session, where the container and its commits are discarded after the
    session), push the working branch before the session ends so the
    work is not lost. This exception covers branch pushes only — never
    tags, and never opening a PR unasked.
- **Tags are not created during development batches.** They are
  generated on the maintainer's local device: after cloning or pulling
  the repo there, create annotated tags from the commit history (batch
  and milestone commits) and push them to origin from that device.
- **Tag convention:** annotated semver tags `vMAJOR.MINOR.PATCH`
  (e.g. `v0.1.0`). Bump PATCH for an ordinary batch, MINOR for a
  feature milestone, MAJOR for breaking/protocol changes. Keep the
  `version` in `pixi.toml` in sync with the latest tag.

## Environment and build (pixi)

The toolchain is managed with **[pixi](https://pixi.sh)** — do not install
JDKs or build tools any other way, and do not introduce Gradle/Maven
without maintainer approval.

Install pixi if missing:

- Linux/macOS: `curl -fsSL https://pixi.sh/install.sh | sh`
- Windows: `powershell -ExecutionPolicy Bypass -c "irm -useb https://pixi.sh/install.ps1 | iex"`

Rules:

- **`pixi.toml` and `pixi.lock` are git-tracked — always.** Commit both
  whenever dependencies or tasks change. Never add them to `.gitignore`.
- The `.pixi/` environment directory is ignored and must stay ignored.
- The lockfile is solved for `linux-64`, `win-64`, `osx-64`, `osx-arm64`;
  keep all four platforms when changing dependencies.
- The JDK comes from conda-forge (`openjdk >=25`, i.e. Java 25).

Tasks (run from the repo root):

- `pixi run build` — compiles `src/main/java` (entry `localreceiver.Main`) into
  `build/classes` and packages `dist/localreceiver.jar` with the embedded
  webroot from `src/main/resources`.
- `pixi run run` — builds then runs the jar.
- `pixi run clean` — removes `build/` and `dist/`.

The PWA has **no build step** (vanilla-only constraint): its assets live
in `src/main/resources/webroot/` and are packaged into the jar as-is.

Tests are not wired as pixi tasks (they need Node.js, which is outside
the pixi env by design) — see Testing for how to run them, and state
plainly in your summary what was and was not verified.

## Coding conventions

- **Java** (`src/main/java`): standard Java idioms, 4-space indent,
  javadoc/comments where they carry non-obvious constraints. Pure JDK
  only — no third-party dependencies without maintainer approval.
- **Webroot** (`src/main/resources/webroot/`): the mandated style is
  the opposite of the Java one — no indentation, no comments, lines
  under 100 chars; see "Webroot code style" under Hard constraints.

## Testing

QR tests live in `tests/qr/` (`npm install` there once, then
`node qr.test.mjs`): encoder round-trips decoded with jsqr plus the
live `/qr.png` endpoint. Run them for any change touching
`localreceiver.util.QrCode` or `QrPngHandler`.

Registry tests live in `tests/server/` (single-file Java,
headless-safe): `java -cp dist/localreceiver.jar tests/server/DevicesTest.java`
— pairing name validation, code consumption, and rename: that it
leaves the device's folder alone, that three devices sharing one root
survive a rename, and the legacy uppercase case. Run for any change to
`Devices`.

TLS tests live in `tests/server/` (single-file Java, headless-safe):
`java -cp dist/localreceiver.jar tests/server/TlsSupportTest.java` — the
random per-installation keystore password, owner-only permissions,
migration off the old fixed password with the CA left intact, the CA
fingerprint (cross-checked against the PEM's own DER bytes), and that
a corrupt CA store errors instead of silently re-rooting trust. Run
for any change to `TlsSupport`. The gold end-to-end check stays
`curl --cacert ~/.config/localreceiver/ca.crt https://localhost:<port>/`
with no `-k`.

Link-safety tests live in `tests/gui/` (single-file Java,
headless-safe):
`java -Djava.awt.headless=true -cp dist/localreceiver.jar tests/gui/LinkSafetyTest.java`
— that `stripPairCode` removes a live pairing code from every URL
shape while leaving other parameters alone. Run for any change to how
the window launches a browser.

L&F tests live in `tests/laf/` (single-file Java, headless-safe):
`java -Djava.awt.headless=true -cp dist/localreceiver.jar tests/laf/LafTest.java`
— token contrast (≥4.5:1) across all four language×scheme combos,
embedded-font CJK coverage, and offscreen renders of every themed
control. Run it for any change under `src/main/java/jacross/`.

Browser tests live in `tests/browser/` (plain Node scripts, exit 0/1):
upload, upload-resume, download-resume, folder-upload, cancel,
fileops, zip-download, inline-view, dir-browse, pairing, and
subdir-acl `.test.mjs` files; shared setup in `lib.mjs`, orchestrated
by `run.sh` (starts a headless `--open` server on a temp dir, waits
for readiness — never a fixed sleep, the first HTTPS start generates
TLS material — and runs them all; `LOCALRECEIVER_SCHEME=https` reruns the
suite over TLS). Upload tests must confirm the queue (`#upload-button`)
after `setInputFiles` — nothing uploads unconfirmed. Tests covering
default postures (dir-browse, pairing) spawn their own servers with
the flags they need — pairing uses `LOCALRECEIVER_CONFIG_DIR` so test
devices never touch the real config. They need Node.js, the `playwright`
package, and Chromium — deliberately not in the pixi env to keep it
lean. Run every one of them before finishing a batch that touches
transfer code, and add a test when adding a transfer behavior.

```sh
pixi run build
sh tests/browser/run.sh
```

## Cloud session notes (Claude Code remote containers)

Facts future agent sessions will otherwise rediscover the hard way:

- **pixi.sh is blocked** by the network policy (CONNECT 403). Install
  pixi by downloading the binary from GitHub releases instead:
  `https://github.com/prefix-dev/pixi/releases/latest/download/pixi-x86_64-unknown-linux-musl.tar.gz`
  → extract to `~/.pixi/bin/pixi` and add to PATH. conda-forge is
  reachable, so `pixi install` works normally after that.
- **Tag pushes are rejected** (403): the session git proxy only accepts
  pushes to the designated `claude/...` branch ref. Branch pushes work;
  `git push origin <tag>` cannot. The GitHub MCP toolset has no
  tag/release creation either. Hence the tags-on-maintainer-device rule.
- **Browser testing works in-container**: Node 22 at `/opt/node22`,
  global playwright module at
  `/opt/node22/lib/node_modules/playwright/index.mjs` (NODE_PATH does
  not apply to ESM imports — import by absolute path or set
  `PLAYWRIGHT_MODULE`), Chromium at
  `/opt/pw-browsers/chromium-1194/chrome-linux/chrome` (set
  `CHROMIUM_BIN`; the exact versioned directory may change — check
  `/opt/pw-browsers/`). `http://localhost` is a secure context, so
  OPFS and service workers are fully testable headlessly.
- **Do not `pkill -f` with a pattern that appears in your own command
  line** (e.g. `pkill -f localreceiver.jar`) — it kills your own shell. Kill
  Java test servers via `for pid in $(pgrep -x java); do kill $pid; done`.
- Java processes pick up proxy settings via `JAVA_TOOL_OPTIONS`
  automatically; harmless "Picked up JAVA_TOOL_OPTIONS" lines appear on
  stderr.

## Releases

`CHANGELOG` (no file extension, repo root) is the changelog and the
single source of truth for GitHub release notes. Section format:
`## vX.Y.Z — title (YYYY-MM-DD)` followed by bullet notes; batches
accumulate bullets under `## Unreleased`.

To cut a release: rename `## Unreleased` to the new version section
(start a fresh empty `## Unreleased` above it), bump `version` in
`pixi.toml` **and `Main.VERSION`** (shown in the window title so users
can tell which jar they are running), merge to `main`, then trigger
`.github/workflows/release.yml` (workflow_dispatch). The workflow
parses CHANGELOG and, per version section, updates the existing GitHub
release's title/notes or creates a missing release tagged at the
triggering commit — so add one new version section per run and trigger
from the merge commit that should carry the tag. It also builds the
jar (Temurin 25) and attaches it to the newest release under the
**versioned asset name `localreceiver_v{x}r{y}n{z}.jar`** for version
v{x}.{y}.{z} (e.g. v0.17.0 → `localreceiver_v0r17n0.jar`) — never plain
`localreceiver.jar`.
Editing past notes is fine: edit CHANGELOG, merge, re-trigger — the
sync is idempotent. This workflow exists because cloud sessions cannot
push tags; from a local device, plain `git tag` + `git push origin
<tag>` works too, but keep CHANGELOG authoritative for the notes.

## Directory map

```
localReceiver/
├── AGENT.md              # this guide
├── CHANGELOG             # changelog; source of GitHub release notes
├── LICENSE               # GPL-3.0
├── README.md             # user-facing docs, bilingual en-GB + zh-Hant-TW
├── .gitignore            # Java template + pixi env + build outputs
├── .gitattributes        # pixi.lock merge/linguist settings
├── pixi.toml             # pixi workspace: deps, platforms, tasks (tracked)
├── pixi.lock             # pixi lockfile, all 4 platforms (tracked)
├── tests/browser/        # Node+Playwright E2E tests (see Testing)
├── tests/qr/             # QR encoder + /qr.png decoder tests
├── tests/gui/            # headless pairing-link safety test
├── tests/laf/            # headless JaCross L&F render/contrast test
└── src/main/
    ├── java/jacross/     # Tier 0 design system: tokens, palettes,
    │   │                 #   Platform probe, JaCrossLaf (+ plaf/ delegates)
    │   └── ...
    ├── java/localreceiver/
    │   ├── Main.java             # entry point; GUI or --headless
    │   ├── Config.java           # ~/.config/localreceiver/config.properties
    │   ├── util/QrCode.java      # pure-JDK QR encoder (v1-5, ECC M)
    │   ├── gui/ServerWindow.java # Swing control window (IP picker, QR)
    │   ├── gui/FolderPicker.java # folder dialog (JFileChooser is blank
    │   │                         #   under JaCrossLaf — never use it)
    │   └── server/
    │       ├── LocalReceiverServer.java  # HttpServer wiring, LAN addresses
    │       ├── Devices.java       # pairing codes, sessions, grants
    │       ├── PairHandler.java   # /api/pair, /api/session
    │       ├── WebRootHandler.java# embedded PWA assets from the jar
    │       ├── FilesHandler.java  # /files/: listings, Range downloads
    │       ├── UploadHandler.java # /api/upload/: chunked resumable
    │       ├── FileOpsHandler.java# /api/files/: delete, rename, mkdir, move
    │       ├── TrashHandler.java  # /api/trash: recycle bin, restore, purge
    │       ├── QrPngHandler.java  # /qr.png: QR of the site URL
    │       ├── CaCertHandler.java # /ca.crt + /ca-fingerprint
    │       └── TlsSupport.java    # CA + server cert generation
    └── resources/
        ├── jacross/
        │   ├── NotoSansTC-Regular.ttf  # embedded UI font (SIL OFL);
        │   │                           #   MUST stay glyf/TrueType —
        │   │                           #   CFF OTFs render as a fallback
        │   │                           #   face on some JDK builds
        │   └── OFL.txt                 # its licence — ships with the font
        ├── localreceiver/
        │   └── icon.png                # window/taskbar icon (dark bg)
        └── webroot/
            ├── index.html            # app shell
            ├── cert-help.html        # per-platform CA install guide
            ├── style.css             # vanilla CSS, light/dark
            ├── app.js                # UI, browser, transfer orchestration
            ├── uploader.js           # upload worker (OPFS staging)
            ├── downloader.js         # download worker (OPFS staging)
            ├── cert-check.js         # shows the CA fingerprint to compare
            │                         #   (name must stay clear of the
            │                         #   /ca-fingerprint context prefix)
            ├── sw.js                 # service worker (shell cache only)
            ├── manifest.webmanifest  # PWA manifest
            ├── icon.svg              # app icon (light bg, navy glyph)
            └── icon-512.png          # PNG icon (manifest + apple-touch)
```

Build outputs go to `build/` and `dist/` (both git-ignored).
Update this map when the source tree grows.

## Known gaps / good first tasks

- The GUI is English-only; no localization scaffolding exists yet.
