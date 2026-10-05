/* Interrupts an upload after one chunk, reloads the page, and verifies
 * the transfer resumes from OPFS sending only the missing chunks. */
import { launchBrowser, CONTEXT_OPTIONS, BASE, SERVE_DIR } from "./lib.mjs";
import { createHash, randomBytes } from "node:crypto";
import { writeFileSync, readFileSync, existsSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";

const payload = randomBytes(12 * 1024 * 1024); // 3 chunks at 4 MiB
const srcPath = join(tmpdir(), "localreceiver-test-resume.dat");
writeFileSync(srcPath, payload);
const srcHash = createHash("sha256").update(payload).digest("hex");

const browser = await launchBrowser();
const context = await browser.newContext(CONTEXT_OPTIONS);
const page = await context.newPage();

let allowed = 0;
await context.route("**/api/upload/chunk*", (route) => {
  allowed += 1;
  if (allowed <= 1) route.continue();
  else route.abort();
});

await page.goto(BASE, { waitUntil: "networkidle" });
await page.setInputFiles("#file-input", srcPath);
await page.waitForSelector("#queue-list li", { timeout: 15000 });
await page.click("#upload-button");
await page.waitForSelector("#send-list .status-err", { timeout: 60000 });
console.log("interrupted as expected:", await page.textContent("#send-list .status-err"));

await context.unroute("**/api/upload/chunk*");
let resumedChunkPuts = 0;
await context.route("**/api/upload/chunk*", (route) => {
  resumedChunkPuts += 1;
  route.continue();
});
await page.reload({ waitUntil: "networkidle" });
await page.waitForSelector("#send-list .status-ok", { timeout: 60000 });
const status = await page.textContent("#send-list .status-ok");
console.log("resumed status:", status);
console.log("chunk PUTs after reload:", resumedChunkPuts, "(3 total chunks, 1 sent before interrupt)");

const finalName = status.replace(/^sent as /, "");
const destPath = join(SERVE_DIR, finalName);
if (!existsSync(destPath)) throw new Error(`file missing: ${destPath}`);
const destHash = createHash("sha256").update(readFileSync(destPath)).digest("hex");
console.log("hash match:", srcHash === destHash);

const staged = await page.evaluate(async () => {
  const root = await navigator.storage.getDirectory();
  try {
    const dir = await root.getDirectoryHandle("localreceiver-outgoing");
    const names = [];
    for await (const [n] of dir.entries()) names.push(n);
    return names;
  } catch { return []; }
});
console.log("OPFS staging after done:", staged);

const ok = srcHash === destHash && resumedChunkPuts < 3 && staged.length === 0;
console.log(ok ? "TEST PASS" : "TEST FAIL");
await browser.close();
process.exit(ok ? 0 : 1);
