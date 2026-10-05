/* Verifies plain-HTTP read-only mode: over --http the shared folder can
 * be read but not changed, while a paired device's virtual "safe"
 * folder (~/localReceiver/<deviceId>/safe) is readable and writable and
 * receives its uploads. Open mode over HTTP has no safe folder at all.
 * Runs its own servers; the upload hint is checked in a browser. */
import { spawn } from "node:child_process";
import { launchBrowser } from "./lib.mjs";
import { createHash } from "node:crypto";
import { writeFileSync, mkdtempSync, mkdirSync, existsSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { tmpdir } from "node:os";

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const jar = join(repoRoot, "dist", "localreceiver.jar");
const pixiJava = join(repoRoot, ".pixi", "envs", "default", "bin", "java");
const java = process.env.JAVA || (existsSync(pixiJava) ? pixiJava : "java");

const token = "cd".repeat(32);
const hash = createHash("sha256").update(token).digest("hex");
const configDir = mkdtempSync(join(tmpdir(), "localreceiver-http-config-"));
const dataDir = mkdtempSync(join(tmpdir(), "localreceiver-http-data-"));
writeFileSync(join(configDir, "devices.properties"), [
  "d.devh.name=dev",
  `d.devh.hash=${hash}`,
  "d.devh.path=",
  "d.devh.read=true",
  "d.devh.write=true",
  "d.devh.browse=true",
].join("\n") + "\n");

const serveDir = mkdtempSync(join(tmpdir(), "localreceiver-http-"));
mkdirSync(join(serveDir, "shared"));
writeFileSync(join(serveDir, "shared", "a.txt"), "shared");

async function start(extraArgs) {
  const server = spawn(java, ["-jar", jar, "--headless", "--http", "--port", "0", ...extraArgs],
    { cwd: serveDir, env: { ...process.env, LOCALRECEIVER_CONFIG_DIR: configDir,
      LOCALRECEIVER_DATA_DIR: dataDir } });
  const port = await new Promise((resolve, reject) => {
    let buf = "";
    const timer = setTimeout(() => reject(new Error("server did not start")), 15000);
    server.stdout.on("data", (d) => {
      buf += d;
      const m = buf.match(/on port (\d+)/);
      if (m) {
        clearTimeout(timer);
        resolve(m[1]);
      }
    });
  });
  return { server, base: `http://localhost:${port}` };
}

let pass = 0;
let fail = 0;
const check = (label, cond) => {
  if (cond) { pass++; console.log(`PASS: ${label}`); }
  else { fail++; console.log(`FAIL: ${label}`); }
};
const post = (base, path, headers) => fetch(`${base}${path}`, { method: "POST", headers });

async function upload(base, headers, path) {
  const key = "ef".repeat(8);
  const init = await post(base, `/api/upload/init?key=${key}&path=${path}&size=2&chunkSize=2`,
    headers);
  if (init.status !== 200) return init.status;
  await fetch(`${base}/api/upload/chunk?key=${key}&index=0`,
    { method: "PUT", headers, body: "ok" });
  return (await post(base, `/api/upload/complete?key=${key}`, headers)).json();
}

const paired = await start([]);
const dev = { Cookie: `localreceiver=${token}`, Accept: "application/json" };
try {
  const { base } = paired;
  const root = await (await fetch(`${base}/files/`, { headers: dev })).json();
  check("root listing offers the virtual safe folder",
    root.entries.some((e) => e.name === "safe" && e.dir));
  check("root listing is read-only", root.fileOps === false);
  check("shared file is readable",
    (await (await fetch(`${base}/files/shared/a.txt`, { headers: dev })).text()) === "shared");
  check("mkdir in the shared folder is 403",
    (await post(base, "/api/files/mkdir?path=shared/new", dev)).status === 403);
  check("delete in the shared folder is 403",
    (await post(base, "/api/files/delete?path=shared/a.txt", dev)).status === 403);
  check("mkdir in safe works",
    (await post(base, "/api/files/mkdir?path=safe/docs", dev)).status === 204);
  const done = await upload(base, dev, "note.txt");
  check("an upload lands in safe", done.name === "safe/note.txt"
    && readFileSync(join(dataDir, "devh", "safe", "note.txt"), "utf8") === "ok");
  check("nothing was written to the shared folder", !existsSync(join(serveDir, "note.txt")));
  const safe = await (await fetch(`${base}/files/safe/`, { headers: dev })).json();
  check("safe listing allows file operations", safe.fileOps === true);
  check("move within safe works",
    (await post(base, "/api/files/move?path=safe/note.txt&to=safe/docs", dev)).status === 200);
  check("move from safe into the shared folder is 403",
    (await post(base, "/api/files/move?path=safe/docs&to=shared", dev)).status === 403);
  check("delete in safe works",
    (await post(base, "/api/files/delete?path=safe/docs", dev)).status === 204);
  const trash = await (await fetch(`${base}/api/trash`, { headers: dev })).json();
  const item = trash.items.find((i) => i.origPath === "safe/docs");
  check("deleted safe item is in the bin", item !== undefined);
  const browser = await launchBrowser();
  const context = await browser.newContext();
  await context.addCookies([{ name: "localreceiver", value: token, url: base }]);
  const page = await context.newPage();
  await page.goto(base, { waitUntil: "networkidle" });
  check("the app explains that HTTP uploads go to the safe folder",
    await page.isVisible("#http-upload-hint"));
  await browser.close();
  check("restore into safe works", item !== undefined && (await post(base,
    `/api/trash/restore?id=${item.id}`, dev)).status === 200
    && existsSync(join(dataDir, "devh", "safe", "docs", "note.txt")));
} finally {
  paired.server.kill();
}

const open = await start(["--open"]);
try {
  const { base } = open;
  const root = await (await fetch(`${base}/files/`, { headers: { Accept: "application/json" } }))
    .json();
  check("open mode over HTTP has no safe folder", !root.entries.some((e) => e.name === "safe"));
  check("open mode over HTTP refuses uploads",
    (await upload(base, {}, "x.txt")) === 403);
  check("open mode over HTTP refuses mkdir",
    (await post(base, "/api/files/mkdir?path=new", {})).status === 403);
} finally {
  open.server.kill();
}

console.log(fail === 0 ? "TEST PASS" : "TEST FAIL");
process.exit(fail === 0 ? 0 : 1);
