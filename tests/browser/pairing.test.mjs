/* Verifies the default pairing posture: without pairing nothing is
 * visible (401s, PWA shows only the pairing screen); pairing via the
 * QR URL (?pair=CODE) creates an isolated per-device folder that
 * cannot see the host's root files; uploads land inside it; a wrong
 * code is rejected; a fresh code is issued once one is consumed. */
import { launchBrowser, CONTEXT_OPTIONS } from "./lib.mjs";
import { spawn } from "node:child_process";
import { writeFileSync, mkdtempSync, existsSync, readdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { tmpdir } from "node:os";

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const jar = join(repoRoot, "dist", "localreceiver.jar");
const pixiJava = join(repoRoot, ".pixi", "envs", "default", "bin", "java");
const java = process.env.JAVA || (existsSync(pixiJava) ? pixiJava : "java");

const serveDir = mkdtempSync(join(tmpdir(), "localreceiver-pairing-"));
const configDir = mkdtempSync(join(tmpdir(), "localreceiver-pairing-config-"));
writeFileSync(join(serveDir, "host-secret.txt"), "not for devices");

const server = spawn(java, ["-jar", jar, "--headless", "--http", "--port", "0"],
  { cwd: serveDir, env: { ...process.env, LOCALRECEIVER_CONFIG_DIR: configDir } });
let stdout = "";
server.stdout.on("data", (d) => { stdout += d; });
const port = await new Promise((resolve, reject) => {
  const timer = setTimeout(() => reject(new Error("server did not start")), 15000);
  const poll = setInterval(() => {
    const m = stdout.match(/on port (\d+)/);
    if (m) {
      clearTimeout(timer);
      clearInterval(poll);
      resolve(m[1]);
    }
  }, 100);
});
const firstCode = await new Promise((resolve, reject) => {
  const timer = setTimeout(() => reject(new Error("no pairing code printed")), 15000);
  const poll = setInterval(() => {
    const m = stdout.match(/Pairing code: ([A-Z2-9]{4}-[A-Z2-9]{4})/);
    if (m) {
      clearTimeout(timer);
      clearInterval(poll);
      resolve(m[1]);
    }
  }, 100);
});
const base = `http://localhost:${port}`;

let pass = 0;
let fail = 0;
const check = (label, cond) => {
  if (cond) { pass++; console.log(`PASS: ${label}`); }
  else { fail++; console.log(`FAIL: ${label}`); }
};

try {
  const files = await fetch(`${base}/files/`);
  check("unpaired /files/ is 401", files.status === 401);
  check("unpaired zip is 401", (await fetch(`${base}/api/zip`)).status === 401);
  check("unpaired upload init is 401",
    (await fetch(`${base}/api/upload/init?key=${"ab".repeat(8)}&name=x&size=1&chunkSize=1`,
      { method: "POST" })).status === 401);
  const session = await (await fetch(`${base}/api/session`)).json();
  check("session says pairing required, not paired",
    session.pairingRequired === true && session.paired === false);
  check("wrong code rejected with 403",
    (await fetch(`${base}/api/pair?code=WRONG-CODE&name=x`, { method: "POST" })).status === 403);
  check("invalid device name rejected with 400",
    (await fetch(`${base}/api/pair?code=${firstCode}&name=Bad-Name`, { method: "POST" }))
      .status === 400);
  check("missing device name rejected with 400",
    (await fetch(`${base}/api/pair?code=${firstCode}`, { method: "POST" })).status === 400);

  const browser = await launchBrowser();
  const context = await browser.newContext(CONTEXT_OPTIONS);
  const page = await context.newPage();

  // Unpaired: only the pairing screen is visible.
  await page.goto(`${base}/`, { waitUntil: "networkidle" });
  check("pairing screen shown when unpaired",
    await page.evaluate(() => !document.getElementById("pair-section").hidden));
  check("file browser hidden when unpaired",
    await page.evaluate(() => document.getElementById("browse-section").hidden));

  // Opening the QR URL prefills the code; the user must name the device.
  await page.goto(`${base}/?pair=${firstCode}`, { waitUntil: "networkidle" });
  check("QR URL prefills the pairing code",
    (await page.inputValue("#pair-code")) === firstCode);
  await page.fill("#pair-name", "phone_a");
  await page.click("#pair-form button");
  await page.waitForFunction(() => document.getElementById("pair-section").hidden,
    { timeout: 15000 });
  const paired = await page.evaluate(async () => await (await fetch("/api/session")).json());
  check("named pairing succeeds", paired.paired === true && paired.name === "phone_a");
  check("pairing screen gone after pairing",
    await page.evaluate(() => document.getElementById("pair-section").hidden));
  check("duplicate name rejected with 409",
    (await fetch(`${base}/api/pair?code=ANYC-ODEX&name=phone_a`, { method: "POST" }))
      .status === 409);

  // Isolation: the device sees its own empty folder, not the root.
  const listing = await page.evaluate(async () =>
    await (await fetch("/files/", { headers: { Accept: "application/json" } })).json());
  check("device listing does not show host files",
    !listing.entries.some((e) => e.name === "host-secret.txt"));
  check("device folder created with the chosen name",
    existsSync(join(serveDir, "phone_a")));

  // Upload lands inside the device's folder.
  writeFileSync(join(serveDir, "..", "pair-upload.txt"), "scoped");
  await page.setInputFiles("#file-input", join(serveDir, "..", "pair-upload.txt"));
  await page.waitForSelector("#queue-list li", { timeout: 15000 });
  await page.click("#upload-button");
  await page.waitForSelector("#send-list .status-ok", { timeout: 60000 });
  check("upload landed in the device folder",
    existsSync(join(serveDir, "phone_a", "pair-upload.txt")));
  check("upload did not land at the root",
    !existsSync(join(serveDir, "pair-upload.txt")));

  // The consumed code no longer works, and a fresh one was issued.
  check("used code cannot pair again",
    (await fetch(`${base}/api/pair?code=${firstCode}&name=phone_b`, { method: "POST" }))
      .status === 403);
  const codes = [...stdout.matchAll(/Pairing code: ([A-Z2-9]{4}-[A-Z2-9]{4})/g)];
  check("a fresh code was printed after pairing",
    codes.length >= 2 && codes[codes.length - 1][1] !== firstCode);

  await browser.close();
} finally {
  server.kill();
}

console.log(fail === 0 ? "TEST PASS" : "TEST FAIL");
process.exit(fail === 0 ? 0 : 1);
