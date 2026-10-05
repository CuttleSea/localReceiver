/* Verifies the two-mode behavior: direct /files/ URLs render
 * whitelisted types (HTML sandboxed, text, images) inline in the browser, while clicking any file in
 * the app at / always goes through the managed download. */
import { launchBrowser, CONTEXT_OPTIONS, BASE, SERVE_DIR } from "./lib.mjs";
import { writeFileSync } from "node:fs";
import { join } from "node:path";

writeFileSync(join(SERVE_DIR, "readme-view.txt"), "inline works");
writeFileSync(join(SERVE_DIR, "opaque.bin"), Buffer.from([1, 2, 3, 4]));
writeFileSync(join(SERVE_DIR, "page-view.html"),
  "<!doctype html><title>Viewed</title><h1 id=h>html renders</h1>"
  + "<script>document.getElementById('h').textContent = 'script ran';</script>"
  + "<img src=\"https://example.com/beacon.png\">");
// 1x1 transparent PNG.
writeFileSync(join(SERVE_DIR, "pixel-view.png"), Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
  "base64"));

const browser = await launchBrowser();
const context = await browser.newContext(CONTEXT_OPTIONS);
const page = await context.newPage();

let pass = 0;
let fail = 0;
const check = (label, cond) => {
  if (cond) { pass++; console.log(`PASS: ${label}`); }
  else { fail++; console.log(`FAIL: ${label}`); }
};

// Direct /files/ URL of a whitelisted type: renders inline, no download.
await page.goto(`${BASE}/files/readme-view.txt`, { waitUntil: "domcontentloaded" });
check("txt renders inline at /files/ URL",
  (await page.evaluate(() => document.body.innerText)).includes("inline works"));

// HTML renders inline, but sandboxed: its script never runs, it has
// an opaque origin (no cookies or storage), and it loads nothing from
// other hosts.
const external = [];
const blocked = [];
page.on("requestfinished", (r) => { if (!r.url().startsWith(BASE)) external.push(r.url()); });
page.on("requestfailed", (r) => { if (!r.url().startsWith(BASE)) blocked.push(r.failure()?.errorText); });
const htmlResponse = await page.goto(`${BASE}/files/page-view.html`, { waitUntil: "load" });
check("html is served as text/html",
  (htmlResponse.headers()["content-type"] || "").startsWith("text/html"));
check("html is served inline",
  (htmlResponse.headers()["content-disposition"] || "").startsWith("inline"));
check("html renders inline at /files/ URL, its script did not run",
  (await page.textContent("#h")) === "html renders");
check("html page title is shown", (await page.title()) === "Viewed");
const csp = htmlResponse.headers()["content-security-policy"] || "";
check("html CSP sandboxes without scripts or same-origin",
  /(^|;)\s*sandbox\s*;/.test(csp) && !csp.includes("allow-scripts") && !csp.includes("allow-same-origin"));
check("html CSP forbids framing", csp.includes("frame-ancestors 'none'"));
check("html loaded nothing from other hosts", external.length === 0);
check("html's external image was blocked by the CSP", blocked.includes("csp"));

// Images render inline as images.
const imgResponse = await page.goto(`${BASE}/files/pixel-view.png`, { waitUntil: "load" });
check("png is served inline as image/png",
  imgResponse.headers()["content-type"] === "image/png"
  && (imgResponse.headers()["content-disposition"] || "").startsWith("inline"));
check("png renders as an image", (await page.locator("img").count()) === 1);

// Direct /files/ URL of a non-whitelisted type: downloads (attachment).
const directDownload = page.waitForEvent("download", { timeout: 30000 });
await page.goto(`${BASE}/files/opaque.bin`).catch(() => {});
check("bin downloads at /files/ URL", (await directDownload).suggestedFilename() === "opaque.bin");

// In the app at /: clicking ANY file — including a viewable one —
// stays a managed download, never an inline view.
await page.goto(BASE, { waitUntil: "networkidle" });
const pagesBefore = context.pages().length;
await page.click(`#server-list a:text("readme-view.txt")`);
await page.waitForSelector("#receive-list .status-ok", { timeout: 60000 });
check("app click on txt is a managed download",
  (await page.textContent("#receive-list .status-ok")) === "saved");
check("no viewer tab opened from the app", context.pages().length === pagesBefore);

console.log(fail === 0 ? "TEST PASS" : "TEST FAIL");
await browser.close();
process.exit(fail === 0 ? 0 : 1);
