/* Verifies, over HTTPS:
 *  - a device whose working folder is its device folder
 *    (<dataDir>/<deviceId>) sees and uses its safe folder there, while
 *    .trash/.uploads stay hidden and the safe folder itself cannot be
 *    renamed or deleted;
 *  - cross-site writes are refused by the Origin check;
 *  - every response forbids framing and carries a CSP;
 *  - requests naming an unknown host (DNS rebinding) are refused.
 * Runs its own server with Node's fetch only (no browser needed). */
import { spawn } from "node:child_process";
import { request } from "node:https";
import { createHash } from "node:crypto";
import { writeFileSync, mkdtempSync, mkdirSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { tmpdir } from "node:os";

process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0";
const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const jar = join(repoRoot, "dist", "localreceiver.jar");
const pixiJava = join(repoRoot, ".pixi", "envs", "default", "bin", "java");
const java = process.env.JAVA || (existsSync(pixiJava) ? pixiJava : "java");

const token = "ef".repeat(32);
const hash = createHash("sha256").update(token).digest("hex");
const configDir = mkdtempSync(join(tmpdir(), "localreceiver-safehttps-config-"));
const serveDir = mkdtempSync(join(tmpdir(), "localreceiver-safehttps-"));
// The data dir sits inside the shared folder so a device can be pointed at its own device folder.
const dataDir = join(serveDir, "lrdata");
mkdirSync(join(dataDir, "devs", ".trash"), { recursive: true });
mkdirSync(join(dataDir, "devs", ".uploads"), { recursive: true });
mkdirSync(join(dataDir, "devs", "safe"), { recursive: true });
writeFileSync(join(dataDir, "devs", "safe", "kept.txt"), "kept");
mkdirSync(join(dataDir, "other", "safe"), { recursive: true });
writeFileSync(join(dataDir, "other", "safe", "theirs.txt"), "theirs");
writeFileSync(join(configDir, "devices.properties"), [
  "d.devs.name=dev",
  `d.devs.hash=${hash}`,
  "d.devs.path=lrdata/devs",
  "d.devs.read=true",
  "d.devs.write=true",
  "d.devs.browse=true",
].join("\n") + "\n");

const server = spawn(java, ["-jar", jar, "--headless", "--https", "--port", "0"],
  { cwd: serveDir, env: { ...process.env, LOCALRECEIVER_CONFIG_DIR: configDir,
    LOCALRECEIVER_DATA_DIR: dataDir } });
let stdout = "";
const port = await new Promise((resolve, reject) => {
  const timer = setTimeout(() => reject(new Error("server did not start")), 30000);
  server.stdout.on("data", (d) => {
    stdout += d;
    const m = stdout.match(/on port (\d+)/);
    if (m) {
      clearTimeout(timer);
      resolve(m[1]);
    }
  });
});
const base = `https://localhost:${port}`;
const dev = { Cookie: `localreceiver=${token}`, Accept: "application/json" };
/* GET with an explicit Host header (fetch does not allow setting it). */
const withHost = (host, accept = "*/*") => new Promise((resolve, reject) => {
  const req = request({ host: "localhost", port, path: "/", method: "GET",
    headers: { Host: host, Accept: accept }, rejectUnauthorized: false }, (res) => {
    let body = "";
    res.on("data", (d) => { body += d; });
    res.on("end", () => resolve({ status: res.statusCode, type: res.headers["content-type"], body }));
  });
  req.on("error", reject);
  req.end();
});
const statusWithHost = async (host) => (await withHost(host)).status;
const post = (path, headers = dev) => fetch(`${base}${path}`, { method: "POST", headers });

let pass = 0;
let fail = 0;
const check = (label, cond) => {
  if (cond) { pass++; console.log(`PASS: ${label}`); }
  else { fail++; console.log(`FAIL: ${label}`); }
};

try {
  const root = await (await fetch(`${base}/files/`, { headers: dev })).json();
  const names = root.entries.map((e) => e.name);
  check("HTTPS listing of the device folder shows safe", names.includes("safe"));
  check("HTTPS listing hides .trash and .uploads",
    !names.includes(".trash") && !names.includes(".uploads"));
  check("HTTPS listing allows file operations", root.fileOps === true);
  check("a file in safe is readable over HTTPS",
    (await (await fetch(`${base}/files/safe/kept.txt`, { headers: dev })).text()) === "kept");
  check(".trash is not reachable",
    (await fetch(`${base}/files/.trash/`, { headers: dev })).status === 404);
  check("another device's safe folder is not reachable",
    (await fetch(`${base}/files/..%2fother%2fsafe%2ftheirs.txt`, { headers: dev })).status === 404);
  check("mkdir inside safe works over HTTPS",
    (await post("/api/files/mkdir?path=safe/docs")).status === 204
    && existsSync(join(dataDir, "devs", "safe", "docs")));
  check("the safe folder itself cannot be renamed",
    (await post("/api/files/rename?path=safe&to=other")).status === 404
    && existsSync(join(dataDir, "devs", "safe", "kept.txt")));
  check("the safe folder itself cannot be deleted",
    (await post("/api/files/delete?path=safe")).status === 404
    && existsSync(join(dataDir, "devs", "safe", "kept.txt")));
  check("the startup output prints the certificate fingerprint",
    /Certificate SHA-256: [0-9A-F]{2}(:[0-9A-F]{2}){31}/.test(stdout));
  check("the fingerprint is no longer served over the network",
    (await fetch(`${base}/ca-fingerprint`)).status === 404);

  check("a cross-site write is refused",
    (await post("/api/files/mkdir?path=safe/evil", { ...dev, Origin: "https://evil.example" }))
      .status === 403 && !existsSync(join(dataDir, "devs", "safe", "evil")));
  check("Sec-Fetch-Site: cross-site is refused",
    (await post("/api/files/mkdir?path=safe/evil2", { ...dev, "Sec-Fetch-Site": "cross-site" }))
      .status === 403);
  check("Origin null is refused",
    (await post("/api/files/mkdir?path=safe/evil3", { ...dev, Origin: "null" })).status === 403);
  check("a same-origin write passes",
    (await post("/api/files/mkdir?path=safe/mine",
      { ...dev, Origin: base, "Sec-Fetch-Site": "same-origin" })).status === 204);
  check("a cross-site read still works (GET is not a write)",
    (await fetch(`${base}/files/safe/kept.txt`, { headers: { ...dev, Origin: "https://evil.example" } }))
      .status === 200);

  for (const path of ["/", "/files/", "/files/safe/kept.txt", "/api/session", "/nope"]) {
    const res = await fetch(`${base}${path}`, { headers: { Cookie: dev.Cookie } });
    const csp = res.headers.get("content-security-policy") || "";
    check(`${path} forbids framing`, res.headers.get("x-frame-options") === "DENY"
      && csp.includes("frame-ancestors 'none'"));
  }
  check("a rebinding host name is refused", (await statusWithHost(`evil.example:${port}`)) === 421);
  check("a public IP as host is refused", (await statusWithHost("8.8.8.8")) === 421);
  const page = await withHost("evil.example", "text/html,application/xhtml+xml");
  check("a browser gets a readable 421 page", page.status === 421
    && page.type.startsWith("text/html") && page.body.includes("doesn't reach localReceiver")
    && !page.body.includes("evil.example"));
  check("localhost is accepted", (await statusWithHost(`localhost:${port}`)) === 200);
  check("127.0.0.1 is accepted", (await statusWithHost(`127.0.0.1:${port}`)) === 200);

  const app = await fetch(`${base}/`);
  check("the app shell CSP allows only its own scripts",
    (app.headers.get("content-security-policy") || "").includes("script-src 'self'"));
} finally {
  server.kill();
}

console.log(fail === 0 ? "TEST PASS" : "TEST FAIL");
process.exit(fail === 0 ? 0 : 1);
