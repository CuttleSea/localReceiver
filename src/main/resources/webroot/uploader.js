"use strict";

const OPFS_DIR = "localreceiver-outgoing";

self.onmessage = async (e) => {
const msg = e.data;
try {
if (msg.cmd === "upload") {
await upload(msg);
} else if (msg.cmd === "resume") {
await resume(msg);
}
} catch (err) {
self.postMessage({ type: "error", key: msg.key, message: String(err && err.message || err) });
}
};

async function opfsDir() {
const root = await navigator.storage.getDirectory();
return root.getDirectoryHandle(OPFS_DIR, { create: true });
}

async function upload({ key, file, relPath, chunkSize, concurrency }) {
const path = relPath || file.name;
let staged = false;
try {
const dir = await opfsDir();
const binHandle = await dir.getFileHandle(`${key}.bin`, { create: true });
const access = await binHandle.createSyncAccessHandle();
try {
access.truncate(0);
const reader = file.stream().getReader();
let offset = 0;
for (;;) {
const { done, value } = await reader.read();
if (done) break;
access.write(value, { at: offset });
offset += value.byteLength;
}
access.flush();
} finally {
access.close();
}
const metaHandle = await dir.getFileHandle(`${key}.json`, { create: true });
const meta = await metaHandle.createSyncAccessHandle();
try {
meta.truncate(0);
meta.write(new TextEncoder().encode(
JSON.stringify({ key, name: path, size: file.size, chunkSize, mtime: file.lastModified })
), { at: 0 });
meta.flush();
} finally {
meta.close();
}
staged = true;
} catch {
staged = false;
}
await transfer({
key,
name: path,
size: file.size,
chunkSize,
concurrency,
mtime: file.lastModified,
blob: staged ? null : file,
});
}

async function resume({ key, concurrency }) {
const dir = await opfsDir();
const metaHandle = await dir.getFileHandle(`${key}.json`);
const meta = JSON.parse(await (await metaHandle.getFile()).text());
await transfer({ ...meta, concurrency, blob: null });
}

async function transfer({ key, name, size, chunkSize, concurrency = 3, mtime, blob }) {
let source = blob;
if (!source) {
const dir = await opfsDir();
source = await (await dir.getFileHandle(`${key}.bin`)).getFile();
}

const initRes = await fetch(
`/api/upload/init?key=${key}&path=${encodeURIComponent(name)}&size=${size}` +
`&chunkSize=${chunkSize}${mtime > 0 ? `&mtime=${mtime}` : ""}`,
{ method: "POST" }
);
if (!initRes.ok) throw new Error(`init failed (${initRes.status})`);
const { chunkCount, have } = await initRes.json();

const haveSet = new Set(have);
const pending = [];
for (let i = 0; i < chunkCount; i++) {
if (!haveSet.has(i)) pending.push(i);
}
let done = chunkCount - pending.length;
self.postMessage({ type: "progress", key, done, total: chunkCount });

let next = 0;
const pump = async () => {
while (next < pending.length) {
const index = pending[next++];
const start = index * chunkSize;
const bytes = await source.slice(start, Math.min(size, start + chunkSize)).arrayBuffer();
let digestParam = "";
if (self.crypto && self.crypto.subtle) {
const hash = new Uint8Array(await self.crypto.subtle.digest("SHA-256", bytes));
digestParam = "&sha256=" + [...hash].map((b) => b.toString(16).padStart(2, "0")).join("");
}
await putWithRetry(`/api/upload/chunk?key=${key}&index=${index}${digestParam}`, bytes);
done++;
self.postMessage({ type: "progress", key, done, total: chunkCount });
}
};
await Promise.all(
Array.from({ length: Math.min(concurrency, Math.max(pending.length, 1)) }, pump));

const completeRes = await fetch(`/api/upload/complete?key=${key}`, { method: "POST" });
if (!completeRes.ok) throw new Error(`complete failed (${completeRes.status})`);
const { name: finalName } = await completeRes.json();

try {
const dir = await opfsDir();
await dir.removeEntry(`${key}.bin`);
await dir.removeEntry(`${key}.json`);
} catch {
}
self.postMessage({ type: "done", key, name: finalName });
}

async function putWithRetry(url, body, attempts = 4) {
let delay = 500;
for (let i = 1; ; i++) {
try {
const res = await fetch(url, { method: "PUT", body });
if (res.ok) return;
if (res.status >= 400 && res.status < 500 && res.status !== 422) {
throw new Error(`chunk rejected (${res.status})`);
}
} catch (err) {
if (i >= attempts) throw err;
}
if (i >= attempts) throw new Error("chunk upload failed after retries");
await new Promise((r) => setTimeout(r, delay));
delay *= 2;
}
}
