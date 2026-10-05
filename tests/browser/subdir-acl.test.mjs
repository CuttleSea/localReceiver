/* Verifies the per-subfolder deny lists: a device whose registry entry
 * denies reading "secretsub" and writing "readonly" cannot see the
 * former nor write into the latter, while everything else stays fully
 * accessible. Uses a pre-written devices.properties with a known
 * session token (the server stores only its SHA-256). */
import { spawn } from "node:child_process";
// Plain HTTP is read-only (except the safe folder), so this test runs
// over HTTPS with the server's self-signed certificate.
process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0";
import { createHash } from "node:crypto";
import { writeFileSync, mkdtempSync, mkdirSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { tmpdir } from "node:os";

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const jar = join(repoRoot, "dist", "localreceiver.jar");
const pixiJava = join(repoRoot, ".pixi", "envs", "default", "bin", "java");
const java = process.env.JAVA || (existsSync(pixiJava) ? pixiJava : "java");

const token = "ab".repeat(32);
const hash = createHash("sha256").update(token).digest("hex");
const configDir = mkdtempSync(join(tmpdir(), "localreceiver-acl-config-"));
writeFileSync(join(configDir, "devices.properties"), [
  "d.devx.name=dev",
  `d.devx.hash=${hash}`,
  "d.devx.path=dev",
  "d.devx.read=true",
  "d.devx.write=true",
  "d.devx.browse=true",
  "d.devx.denyRead=secretsub",
  "d.devx.denyWrite=readonly",
  "d.devy.name=other",
  `d.devy.hash=${"0".repeat(64)}`,
  "d.devy.path=dev/open",
  "d.devy.read=true",
  "d.devy.write=true",
  "d.devy.browse=true",
].join("\n") + "\n");

const serveDir = mkdtempSync(join(tmpdir(), "localreceiver-acl-"));
mkdirSync(join(serveDir, "dev", "secretsub"), { recursive: true });
mkdirSync(join(serveDir, "dev", "readonly"), { recursive: true });
mkdirSync(join(serveDir, "dev", "open"), { recursive: true });
writeFileSync(join(serveDir, "dev", "secretsub", "hidden.txt"), "hidden");
writeFileSync(join(serveDir, "dev", "readonly", "keep.txt"), "keep");
writeFileSync(join(serveDir, "dev", "open", "hello.txt"), "hello");

const server = spawn(java, ["-jar", jar, "--headless", "--https", "--port", "0"],
  { cwd: serveDir, env: { ...process.env, LOCALRECEIVER_CONFIG_DIR: configDir,
    LOCALRECEIVER_DATA_DIR: mkdtempSync(join(tmpdir(), "localreceiver-acl-data-")) } });
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
const base = `https://localhost:${port}`;
const asDevice = { headers: { Cookie: `localreceiver=${token}`, Accept: "application/json" } };

let pass = 0;
let fail = 0;
const check = (label, cond) => {
  if (cond) { pass++; console.log(`PASS: ${label}`); }
  else { fail++; console.log(`FAIL: ${label}`); }
};

try {
  const listing = await (await fetch(`${base}/files/`, asDevice)).json();
  const names = listing.entries.map((e) => e.name);
  check("listing hides the read-denied subfolder", !names.includes("secretsub"));
  check("listing shows the other subfolders",
    names.includes("open") && names.includes("readonly"));

  check("read-denied file is 404, like a missing one",
    (await fetch(`${base}/files/secretsub/hidden.txt`, asDevice)).status === 404);
  check("read-denied listing is 404",
    (await fetch(`${base}/files/secretsub/`, asDevice)).status === 404);
  check("a path outside the device folder is 404",
    (await fetch(`${base}/files/..%2f..%2fetc/`, asDevice)).status === 404);
  check("allowed file reads fine",
    (await fetch(`${base}/files/open/hello.txt`, asDevice)).status === 200);
  check("write-denied folder still reads fine",
    (await fetch(`${base}/files/readonly/keep.txt`, asDevice)).status === 200);

  const key = "cd".repeat(8);
  const initDenied = await fetch(
    `${base}/api/upload/init?key=${key}&path=readonly/new.txt&size=1&chunkSize=1`,
    { method: "POST", headers: asDevice.headers });
  check("upload into write-denied folder is 404", initDenied.status === 404);
  const initAllowed = await fetch(
    `${base}/api/upload/init?key=${key}&path=open/new.txt&size=1&chunkSize=1`,
    { method: "POST", headers: asDevice.headers });
  check("upload into allowed folder inits fine", initAllowed.status === 200);

  check("delete in write-denied folder is 404",
    (await fetch(`${base}/api/files/delete?path=readonly/keep.txt`,
      { method: "POST", headers: asDevice.headers })).status === 404);
  check("delete in allowed folder works",
    (await fetch(`${base}/api/files/delete?path=open/hello.txt`,
      { method: "POST", headers: asDevice.headers })).status === 204);

  check("another device's working folder cannot be renamed",
    (await fetch(`${base}/api/files/rename?path=open&to=moved`,
      { method: "POST", headers: asDevice.headers })).status === 403
      && existsSync(join(serveDir, "dev", "open")));

  check("zip of a read-denied folder is 404",
    (await fetch(`${base}/api/zip?path=secretsub`, asDevice)).status === 404);
  const zip = await fetch(`${base}/api/zip`, asDevice);
  const zipBytes = Buffer.from(await zip.arrayBuffer());
  check("zip of the root succeeds", zip.status === 200 && zipBytes.length > 0);
  check("zip of the root omits read-denied entries",
    !zipBytes.includes(Buffer.from("secretsub/hidden.txt")));
} finally {
  server.kill();
}

console.log(fail === 0 ? "TEST PASS" : "TEST FAIL");
process.exit(fail === 0 ? 0 : 1);
