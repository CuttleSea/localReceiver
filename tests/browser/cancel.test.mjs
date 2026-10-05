/* Cancels an in-flight upload and verifies full cleanup: no server
 * staging, no OPFS staging, nothing to resume after reload. */
import { launchBrowser, CONTEXT_OPTIONS, BASE, SERVE_DIR } from "./lib.mjs";
import { randomBytes } from "node:crypto";
import { writeFileSync, existsSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";

const payload = randomBytes(12 * 1024 * 1024); // 3 chunks
const srcPath = join(tmpdir(), "localreceiver-test-cancel.dat");
writeFileSync(srcPath, payload);

const browser = await launchBrowser();
const context = await browser.newContext(CONTEXT_OPTIONS);
const page = await context.newPage();

// Slow every chunk PUT so there is a wide window to cancel mid-flight.
await context.route("**/api/upload/chunk*", async (route) => {
  await new Promise((r) => setTimeout(r, 1500));
  route.continue();
});

await page.goto(BASE, { waitUntil: "networkidle" });
await page.setInputFiles("#file-input", srcPath);
await page.waitForSelector("#queue-list li", { timeout: 15000 });
await page.click("#upload-button");
await page.waitForSelector("#send-list .cancel", { timeout: 30000 });
await page.waitForTimeout(500); // let staging + first PUTs begin
await page.click("#send-list .cancel");
await page.waitForFunction(
  () => [...document.querySelectorAll("#send-list li span")].some((s) => s.textContent === "cancelled"),
  { timeout: 15000 }
);
console.log("row shows cancelled");

// Give async cleanup a moment, then verify everything is gone.
await page.waitForTimeout(2000);

const opfs = await page.evaluate(async () => {
  const root = await navigator.storage.getDirectory();
  try {
    const dir = await root.getDirectoryHandle("localreceiver-outgoing");
    const names = [];
    for await (const [n] of dir.entries()) names.push(n);
    return names;
  } catch { return []; }
});
console.log("OPFS staging after cancel:", opfs);

const partDir = join(process.env.LOCALRECEIVER_DATA_DIR, ".uploads");
const serverStaging = existsSync(partDir) ? readdirSync(partDir) : [];
console.log("server staging after cancel:", serverStaging);

const uploaded = existsSync(join(SERVE_DIR, "localreceiver-test-cancel.dat"));
console.log("file assembled anyway:", uploaded);

// A reload must not resurrect the cancelled transfer.
await page.reload({ waitUntil: "networkidle" });
await page.waitForTimeout(1000);
const resumed = await page.evaluate(() => document.querySelectorAll("#send-list li").length);
console.log("rows after reload:", resumed);

const ok = opfs.length === 0 && serverStaging.length === 0 && !uploaded && resumed === 0;
console.log(ok ? "TEST PASS" : "TEST FAIL");
await browser.close();
process.exit(ok ? 0 : 1);
