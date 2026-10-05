/* Uploads a 10 MiB file through the PWA UI and verifies OPFS staging,
 * the on-disk result hash, and the refreshed listing. */
import { launchBrowser, CONTEXT_OPTIONS, BASE, SCHEME, SERVE_DIR } from "./lib.mjs";
import { createHash, randomBytes } from "node:crypto";
import { writeFileSync, readFileSync, existsSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";

const payload = randomBytes(10 * 1024 * 1024);
const srcPath = join(tmpdir(), "localreceiver-test-upload.dat");
writeFileSync(srcPath, payload);
const srcHash = createHash("sha256").update(payload).digest("hex");

const browser = await launchBrowser();
const page = await (await browser.newContext(CONTEXT_OPTIONS)).newPage();
const errors = [];
page.on("pageerror", (e) => errors.push(`pageerror: ${e.message}`));
page.on("console", (m) => {
  if (m.type() !== "error") return;
  // Over self-signed HTTPS, Chromium refuses the service-worker script
  // (SW needs a fully trusted cert); the app degrades gracefully, so
  // this specific error is expected — everything else still fails the test.
  if (SCHEME === "https" && m.text().includes("SSL certificate error")) return;
  errors.push(`console: ${m.text()}`);
});

// In secure contexts the uploader must attach per-chunk SHA-256 digests.
let digestedChunks = 0;
let totalChunks = 0;
page.on("request", (req) => {
  if (req.url().includes("/api/upload/chunk")) {
    totalChunks++;
    if (req.url().includes("sha256=")) digestedChunks++;
  }
});

await page.goto(BASE, { waitUntil: "networkidle" });

const opfs = await page.evaluate(async () => {
  try { await navigator.storage.getDirectory(); return true; } catch { return false; }
});
console.log("OPFS available:", opfs);

await page.setInputFiles("#file-input", srcPath);
// Nothing uploads until the queue is confirmed.
await page.waitForSelector("#queue-list li", { timeout: 15000 });
const chunksBeforeConfirm = totalChunks;
console.log("queued without uploading:", chunksBeforeConfirm === 0);
await page.click("#upload-button");
await page.waitForSelector("#send-list .status-ok", { timeout: 60000 });
const status = await page.textContent("#send-list .status-ok");
console.log("UI status:", status);

const finalName = status.replace(/^sent as /, "");
const destPath = join(SERVE_DIR, finalName);
if (!existsSync(destPath)) throw new Error(`uploaded file missing: ${destPath}`);
const destHash = createHash("sha256").update(readFileSync(destPath)).digest("hex");
console.log("hash match:", srcHash === destHash);

const listed = await page.textContent("#server-list");
console.log("listed in UI:", listed.includes(finalName));
console.log(`chunk digests: ${digestedChunks}/${totalChunks} requests carried sha256`);

// The upload must keep the source file's modification time.
const { statSync } = await import("node:fs");
const mtimeDelta = Math.abs(statSync(destPath).mtimeMs - statSync(srcPath).mtimeMs);
console.log("mtime preserved (delta ms):", Math.round(mtimeDelta));

if (errors.length) console.log("BROWSER ERRORS:", errors);
const ok = opfs && srcHash === destHash && listed.includes(finalName)
  && totalChunks > 0 && digestedChunks === totalChunks && errors.length === 0
  && chunksBeforeConfirm === 0 && mtimeDelta < 2000;
console.log(ok ? "TEST PASS" : "TEST FAIL");
await browser.close();
process.exit(ok ? 0 : 1);
