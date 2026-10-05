/* Verifies the pure-JDK QR encoder against an independent decoder
 * (jsqr): CLI round-trips spanning versions 1-5, then the live
 * /qr.png endpoint of a freshly started server. Run `npm install`
 * in this directory first. Needs dist/localreceiver.jar built. */
import jsQR from "jsqr";
import { PNG } from "pngjs";
import { execFileSync, spawn } from "node:child_process";
import { readFileSync, existsSync, rmSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { tmpdir } from "node:os";

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const jar = process.env.LOCALRECEIVER_JAR || join(repoRoot, "dist", "localreceiver.jar");
const pixiJava = join(repoRoot, ".pixi", "envs", "default", "bin", "java");
const java = process.env.JAVA || (existsSync(pixiJava) ? pixiJava : "java");

function decodePng(buffer) {
  const png = PNG.sync.read(buffer);
  const result = jsQR(new Uint8ClampedArray(png.data), png.width, png.height);
  return result ? result.data : null;
}

let pass = 0;
let fail = 0;
function check(label, got, want) {
  if (got === want) {
    pass++;
    console.log(`PASS: ${label}`);
  } else {
    fail++;
    console.log(`FAIL: ${label} — wanted [${want}] got [${got}]`);
  }
}

// CLI round-trips, sized to span QR versions 1 through 5.
const samples = [
  "A",
  "http://localhost:4646/",
  "http://192.168.100.200:65535/",
  "0123456789012345678901234567890123456789",
  "This is a longer test payload to push into version four or five territory, ok!",
];
const out = join(tmpdir(), `localreceiver-qr-${process.pid}.png`);
for (const text of samples) {
  execFileSync(java, ["-cp", jar, "localreceiver.util.QrCode", text, out], { stdio: "ignore" });
  check(`roundtrip ${text.length}B`, decodePng(readFileSync(out)), text);
}
rmSync(out, { force: true });

// Live /qr.png endpoint: start a headless server, decode its QR.
// --http: this test talks plain fetch() without a CA bundle.
const server = spawn(java, ["-jar", jar, "--headless", "--http", "--port", "0"], { cwd: tmpdir() });
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
try {
  const png = Buffer.from(await (await fetch(`http://localhost:${port}/qr.png`)).arrayBuffer());
  check("endpoint default", decodePng(png), `http://localhost:${port}/`);
  const custom = Buffer.from(
    await (await fetch(`http://localhost:${port}/qr.png?text=http://192.168.9.9:1234/`)).arrayBuffer());
  check("endpoint ?text=", decodePng(custom), "http://192.168.9.9:1234/");
  const oversized = await fetch(`http://localhost:${port}/qr.png?text=${"a".repeat(120)}`);
  check("endpoint oversize -> 400", oversized.status, 400);
} finally {
  server.kill();
}

console.log(`=== ${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
