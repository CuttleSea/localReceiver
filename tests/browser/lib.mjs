/* Shared setup for localReceiver browser tests.
 *
 * Environment:
 *   LOCALRECEIVER_PORT   port of a running localReceiver server (required)
 *   LOCALRECEIVER_DIR    the server's file root on disk (required)
 *   PLAYWRIGHT_MODULE  import specifier for playwright
 *                      (default "playwright"; in Claude cloud sessions use
 *                      "/opt/node22/lib/node_modules/playwright/index.mjs")
 *   CHROMIUM_BIN  Chromium executable path (default: playwright's own;
 *                 in Claude cloud sessions
 *                 "/opt/pw-browsers/chromium-1194/chrome-linux/chrome")
 */
export async function launchBrowser() {
  const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || "playwright");
  const options = {};
  if (process.env.CHROMIUM_BIN) {
    options.executablePath = process.env.CHROMIUM_BIN;
  }
  return chromium.launch(options);
}

/* Context options every test should use: the server's self-signed cert
 * must be accepted when running the suite over HTTPS. */
export const CONTEXT_OPTIONS = { ignoreHTTPSErrors: true };

export const PORT = process.env.LOCALRECEIVER_PORT || "4646";
export const SCHEME = process.env.LOCALRECEIVER_SCHEME || "https";
export const SERVE_DIR = process.env.LOCALRECEIVER_DIR;
export const BASE = `${SCHEME}://localhost:${PORT}`;

if (!SERVE_DIR) {
  console.error("LOCALRECEIVER_DIR must point at the running server's file root");
  process.exit(2);
}
