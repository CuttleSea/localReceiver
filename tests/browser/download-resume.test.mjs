/* Interrupts a managed download after one ranged chunk, reloads, and
 * verifies resume from OPFS, the saved file's hash, and the deferred
 * staging cleanup on the following visit. */
import { launchBrowser, CONTEXT_OPTIONS, BASE, SERVE_DIR } from "./lib.mjs";
import { createHash, randomBytes } from "node:crypto";
import { writeFileSync, readFileSync } from "node:fs";
import { join } from "node:path";

const payload = randomBytes(9 * 1024 * 1024); // 3 chunks at 4 MiB
const serverFile = join(SERVE_DIR, "big-download.dat");
writeFileSync(serverFile, payload);
const srcHash = createHash("sha256").update(payload).digest("hex");

const browser = await launchBrowser();
const context = await browser.newContext(CONTEXT_OPTIONS);
const page = await context.newPage();
const errors = [];
page.on("pageerror", (e) => errors.push(`pageerror: ${e.message}`));

await page.goto(BASE, { waitUntil: "networkidle" });

let allowed = 0;
await context.route("**/files/big-download.dat", (route) => {
  const headers = route.request().headers();
  if (!headers.range) return route.continue();
  allowed += 1;
  if (allowed <= 1) route.continue();
  else route.abort();
});

await page.click(`#server-list a[href="/files/big-download.dat"]`);
await page.waitForSelector("#receive-list .status-err", { timeout: 60000 });
console.log("interrupted as expected:", await page.textContent("#receive-list .status-err"));

await context.unroute("**/files/big-download.dat");
let rangedAfter = 0;
await context.route("**/files/big-download.dat", (route) => {
  if (route.request().headers().range) rangedAfter += 1;
  route.continue();
});
const downloadPromise = page.waitForEvent("download", { timeout: 120000 });
await page.reload({ waitUntil: "domcontentloaded" });
await page.waitForSelector("#receive-list .status-ok", { timeout: 120000 });
console.log("resume status:", await page.textContent("#receive-list .status-ok"));
console.log("ranged fetches after reload:", rangedAfter, "(3 chunks total, 1 before interrupt)");

const download = await downloadPromise.catch(() => null);
let hashOk = false;
if (download) {
  const savedPath = await download.path();
  hashOk = createHash("sha256").update(readFileSync(savedPath)).digest("hex") === srcHash;
  console.log("downloaded file hash match:", hashOk);
} else {
  console.log("no download event captured");
}

// Cleanup is deferred to the next visit — verify staging is gone then.
await page.reload({ waitUntil: "networkidle" });
const staged = await page.evaluate(async () => {
  const root = await navigator.storage.getDirectory();
  try {
    const dir = await root.getDirectoryHandle("localreceiver-incoming");
    const names = [];
    for await (const [n] of dir.entries()) names.push(n);
    return names;
  } catch { return []; }
});
console.log("OPFS staging after next visit:", staged);
if (errors.length) console.log("BROWSER ERRORS:", errors);

const ok = hashOk && rangedAfter < 3 && staged.length === 0 && errors.length === 0;
console.log(ok ? "TEST PASS" : "TEST FAIL");
await browser.close();
process.exit(ok ? 0 : 1);
