/* Verifies the directory-browse toggle. Default OFF: navigating a
 * browser to a /files/ directory URL still gets the JSON listing, and
 * the PWA's JSON fetches are untouched. With --browse: the browser gets
 * an HTML index page with working links into subfolders and files,
 * while explicit application/json requests keep getting JSON. */
import { launchBrowser, CONTEXT_OPTIONS } from "./lib.mjs";
import { spawn } from "node:child_process";
import { writeFileSync, mkdirSync, mkdtempSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { tmpdir } from "node:os";

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const jar = join(repoRoot, "dist", "localreceiver.jar");
const pixiJava = join(repoRoot, ".pixi", "envs", "default", "bin", "java");
const java = process.env.JAVA || (existsSync(pixiJava) ? pixiJava : "java");

const serveDir = mkdtempSync(join(tmpdir(), "localreceiver-dirbrowse-"));
mkdirSync(join(serveDir, "docs & more"));
writeFileSync(join(serveDir, "docs & more", "note.txt"), "browse works");
writeFileSync(join(serveDir, "top.bin"), Buffer.from([9, 9]));

const startServer = (extraArgs) => {
  const proc = spawn(java,
    ["-jar", jar, "--headless", "--http", "--port", "0", "--open", ...extraArgs],
    { cwd: serveDir });
  const port = new Promise((resolve, reject) => {
    let buf = "";
    const timer = setTimeout(() => reject(new Error("server did not start")), 15000);
    proc.stdout.on("data", (d) => {
      buf += d;
      const m = buf.match(/on port (\d+)/);
      if (m) {
        clearTimeout(timer);
        resolve(m[1]);
      }
    });
  });
  return { proc, port };
};

let pass = 0;
let fail = 0;
const check = (label, cond) => {
  if (cond) { pass++; console.log(`PASS: ${label}`); }
  else { fail++; console.log(`FAIL: ${label}`); }
};

// --- Default posture: toggle OFF, browser navigation still gets JSON.
const off = startServer([]);
const offPort = await off.port;
try {
  const asBrowser = await fetch(`http://localhost:${offPort}/files/`,
    { headers: { Accept: "text/html,application/xhtml+xml" } });
  check("off: browser Accept still gets JSON",
    (asBrowser.headers.get("content-type") || "").includes("application/json"));
  const listing = await asBrowser.json();
  check("off: JSON listing intact", Array.isArray(listing.entries));
} finally {
  off.proc.kill();
}

// --- Toggle ON via --browse: HTML index for browsers, JSON for the app.
const on = startServer(["--browse"]);
const onPort = await on.port;
try {
  const asApp = await fetch(`http://localhost:${onPort}/files/`,
    { headers: { Accept: "application/json" } });
  check("on: application/json still gets JSON",
    (asApp.headers.get("content-type") || "").includes("application/json"));

  const browser = await launchBrowser();
  const page = await (await browser.newContext(CONTEXT_OPTIONS)).newPage();
  await page.goto(`http://localhost:${onPort}/files/`, { waitUntil: "domcontentloaded" });
  check("on: root renders an HTML index",
    await page.evaluate(() => document.querySelectorAll("ul li a").length) >= 2);

  // Follow the folder link (name with space and & exercises escaping).
  await page.click('a:text("docs & more")');
  await page.waitForLoadState("domcontentloaded");
  check("on: subfolder index lists the file",
    (await page.evaluate(() => document.body.innerText)).includes("note.txt"));
  check("on: breadcrumb shows the folder",
    (await page.evaluate(() => document.body.innerText)).includes("docs & more"));

  // A whitelisted file linked from the index renders inline (v0.13.0).
  await page.click('a:text("note.txt")');
  await page.waitForLoadState("domcontentloaded");
  check("on: file link opens the inline view",
    (await page.evaluate(() => document.body.innerText)).includes("browse works"));
  await browser.close();
} finally {
  on.proc.kill();
}

console.log(fail === 0 ? "TEST PASS" : "TEST FAIL");
process.exit(fail === 0 ? 0 : 1);
