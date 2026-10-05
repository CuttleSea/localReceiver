"use strict";

const OPFS_DIR = "localreceiver-incoming";

self.onmessage = async (e) => {
const msg = e.data;
try {
if (msg.cmd === "download") {
await download(msg);
} else if (msg.cmd === "resume") {
await resume(msg);
} else if (msg.cmd === "markDelivered") {
await markDelivered(msg.key);
}
} catch (err) {
self.postMessage({ type: "error", key: msg.key, message: String(err && err.message || err) });
}
};

async function markDelivered(key) {
const dir = await opfsDir();
const metaHandle = await dir.getFileHandle(`${key}.json`);
const meta = JSON.parse(await (await metaHandle.getFile()).text());
meta.delivered = true;
await writeMeta(dir, key, meta);
self.postMessage({ type: "markedDelivered", key });
}

async function opfsDir() {
const root = await navigator.storage.getDirectory();
return root.getDirectoryHandle(OPFS_DIR, { create: true });
}

async function writeMeta(dir, key, meta) {
const handle = await dir.getFileHandle(`${key}.json`, { create: true });
const access = await handle.createSyncAccessHandle();
try {
access.truncate(0);
access.write(new TextEncoder().encode(JSON.stringify(meta)), { at: 0 });
access.flush();
} finally {
access.close();
}
}

/* One random id per download, sent with each of its requests: the server
 * resolves them all against the working folder the download started in. */
function newTransferId() {
const bytes = crypto.getRandomValues(new Uint8Array(16));
return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

class FileChangedError extends Error {}

async function download({ key, path, name, size, etag, chunkSize, concurrency }) {
const dir = await opfsDir();
const meta = { key, path, name, size, etag, chunkSize, have: [], transferId: newTransferId() };
await writeMeta(dir, key, meta);
await transfer(dir, meta, concurrency);
}

async function resume({ key, concurrency }) {
const dir = await opfsDir();
const metaHandle = await dir.getFileHandle(`${key}.json`);
const meta = JSON.parse(await (await metaHandle.getFile()).text());

if (!meta.transferId) meta.transferId = newTransferId();
await refresh(dir, meta);
await transfer(dir, meta, concurrency);
}

/* Re-reads the file's ETag and size; a changed file starts over. */
async function refresh(dir, meta) {
const head = await fetch(meta.path, { method: "HEAD",
headers: { "X-Transfer-Id": meta.transferId } });
if (!head.ok) throw new Error(`file gone (${head.status})`);
if (head.headers.get("ETag") !== meta.etag) {
meta.etag = head.headers.get("ETag");
meta.size = Number(head.headers.get("Content-Length"));
meta.have = [];
await dir.removeEntry(`${meta.key}.bin`).catch(() => {});
}
await writeMeta(dir, meta.key, meta);
}

/* Downloads, restarting from scratch (at most twice) when the file
 * changes on the server mid-download, so bytes of two versions never mix. */
async function transfer(dir, meta, concurrency = 3) {
for (let restarts = 0; ; restarts++) {
try {
await transferOnce(dir, meta, concurrency);
return;
} catch (err) {
if (!(err instanceof FileChangedError) || restarts >= 2) throw err;
meta.etag = null;
await refresh(dir, meta);
}
}
}

async function transferOnce(dir, meta, concurrency) {
const { key, path, size, chunkSize } = meta;
const chunkCount = size === 0 ? 1 : Math.ceil(size / chunkSize);
const haveSet = new Set(meta.have);
const pending = [];
for (let i = 0; i < chunkCount; i++) {
if (!haveSet.has(i)) pending.push(i);
}
let done = chunkCount - pending.length;
self.postMessage({ type: "progress", key, done, total: chunkCount });

const binHandle = await dir.getFileHandle(`${key}.bin`, { create: true });
const access = await binHandle.createSyncAccessHandle();
try {
let next = 0;
const pump = async () => {
while (next < pending.length) {
const index = pending[next++];
const start = index * chunkSize;
const end = Math.min(size, start + chunkSize) - 1;
const bytes = await getRangeWithRetry(meta, start, end);
access.write(new Uint8Array(bytes), { at: start });
access.flush();
haveSet.add(index);
meta.have = [...haveSet];
await writeMeta(dir, key, meta);
done++;
self.postMessage({ type: "progress", key, done, total: chunkCount });
}
};
await Promise.all(
Array.from({ length: Math.min(concurrency, Math.max(pending.length, 1)) }, pump));
} finally {
access.close();
}
self.postMessage({ type: "done", key, name: meta.name });
}

async function getRangeWithRetry(meta, start, end, attempts = 4) {
let delay = 500;
for (let i = 1; ; i++) {
try {
const headers = { Range: `bytes=${start}-${end}`, "X-Transfer-Id": meta.transferId };
if (meta.etag) headers["If-Range"] = meta.etag;
const res = await fetch(meta.path, { headers });
if (meta.etag && res.ok && res.headers.get("ETag") !== meta.etag) {
throw new FileChangedError("file changed on the server");
}
if (res.status === 206 || (res.status === 200 && start === 0)) {
const buf = await res.arrayBuffer();
if (buf.byteLength === end - start + 1) return buf;
if (res.status === 200) return buf.slice(start, end + 1);
throw new Error("short range response");
}
if (res.status >= 400 && res.status < 500) {
throw new Error(`range rejected (${res.status})`);
}
} catch (err) {
if (err instanceof FileChangedError || i >= attempts) throw err;
}
if (i >= attempts) throw new Error("chunk download failed after retries");
await new Promise((r) => setTimeout(r, delay));
delay *= 2;
}
}
