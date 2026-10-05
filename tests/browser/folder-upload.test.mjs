/* Uploads a folder through the webkitdirectory picker and verifies the
 * relative directory structure is recreated under the file root. */
import { launchBrowser, CONTEXT_OPTIONS, BASE, SERVE_DIR } from "./lib.mjs";
import { createHash, randomBytes } from "node:crypto";
import { mkdirSync, writeFileSync, readFileSync, existsSync, rmSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";

const src = join(tmpdir(), `localreceiver-folder-${process.pid}`);
rmSync(src, { recursive: true, force: true });
mkdirSync(join(src, "sub", "deeper"), { recursive: true });
const fileA = randomBytes(5 * 1024 * 1024); // multi-chunk
const fileB = randomBytes(1024);
const fileC = randomBytes(2048);
writeFileSync(join(src, "top.bin"), fileA);
writeFileSync(join(src, "sub", "middle.bin"), fileB);
writeFileSync(join(src, "sub", "deeper", "leaf.bin"), fileC);
const hash = (buf) => createHash("sha256").update(buf).digest("hex");

const browser = await launchBrowser();
const page = await (await browser.newContext(CONTEXT_OPTIONS)).newPage();
await page.goto(BASE, { waitUntil: "networkidle" });

await page.setInputFiles("#folder-input", src);
await page.waitForSelector("#queue-list li", { timeout: 15000 });
await page.click("#upload-button");
await page.waitForFunction(
  () => document.querySelectorAll("#send-list .status-ok").length === 3,
  { timeout: 120000 }
);

const folder = src.split("/").pop();
let pass = 0;
let fail = 0;
for (const [rel, buf] of [
  [`${folder}/top.bin`, fileA],
  [`${folder}/sub/middle.bin`, fileB],
  [`${folder}/sub/deeper/leaf.bin`, fileC],
]) {
  const dest = join(SERVE_DIR, rel);
  if (existsSync(dest) && hash(readFileSync(dest)) === hash(buf)) {
    pass++;
    console.log(`PASS: ${rel}`);
  } else {
    fail++;
    console.log(`FAIL: ${rel} missing or corrupted`);
  }
}

rmSync(src, { recursive: true, force: true });
console.log(fail === 0 ? "TEST PASS" : "TEST FAIL");
await browser.close();
process.exit(fail === 0 ? 0 : 1);
