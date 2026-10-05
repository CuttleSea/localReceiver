#!/bin/sh
# Runs all localReceiver browser tests against a freshly started server.
# Requires: dist/localreceiver.jar built (pixi run build), Node.js, playwright,
# and Chromium. See lib.mjs for the environment variables; in Claude
# cloud sessions:
#   PLAYWRIGHT_MODULE=/opt/node22/lib/node_modules/playwright/index.mjs
#   CHROMIUM_BIN=/opt/pw-browsers/chromium-1194/chrome-linux/chrome
set -eu

REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
PORT="${LOCALRECEIVER_PORT:-4655}"
SERVE_DIR="${LOCALRECEIVER_DIR:-$(mktemp -d)}"
# LOCALRECEIVER_SCHEME=https runs the whole suite over TLS (self-signed cert,
# accepted via ignoreHTTPSErrors in the tests). Default http.
SCHEME="${LOCALRECEIVER_SCHEME:-http}"
SCHEME_FLAG="--http"
[ "$SCHEME" = "https" ] && SCHEME_FLAG="--https"

# Prefer the project's pixi-managed JDK (matches the build's class version).
JAVA=java
[ -x "$REPO_ROOT/.pixi/envs/default/bin/java" ] && JAVA="$REPO_ROOT/.pixi/envs/default/bin/java"

cd "$SERVE_DIR"
# --open: pairing is on by default; pairing.test.mjs covers it with
# its own server, the rest of the suite runs in open mode. File
# management is always on (per-device permissions govern it).
"$JAVA" -jar "$REPO_ROOT/dist/localreceiver.jar" --headless --port "$PORT" "$SCHEME_FLAG" --open &
SERVER_PID=$!
trap 'kill "$SERVER_PID" 2>/dev/null || true' EXIT
# Wait for readiness instead of a fixed sleep: the first HTTPS start
# also generates the CA and server certificate, which can exceed 3s.
# (-k only here — the TLS gold test uses --cacert, never -k.)
for i in $(seq 1 60); do
    curl -ks -o /dev/null "$SCHEME://localhost:$PORT/" && break
    sleep 0.5
done

cd "$REPO_ROOT/tests/browser"
FAIL=0
for test in upload.test.mjs upload-resume.test.mjs download-resume.test.mjs folder-upload.test.mjs cancel.test.mjs fileops.test.mjs zip-download.test.mjs inline-view.test.mjs dir-browse.test.mjs pairing.test.mjs subdir-acl.test.mjs; do
    echo "=== $test ==="
    LOCALRECEIVER_PORT="$PORT" LOCALRECEIVER_DIR="$SERVE_DIR" node "$test" || FAIL=1
done
exit "$FAIL"
