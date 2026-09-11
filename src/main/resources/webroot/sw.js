"use strict";

const CACHE = "ttdrop-shell-v16";
const SHELL = ["/", "/style.css", "/app.js", "/uploader.js", "/downloader.js",
"/cert-check.js", "/cert-help.html", "/manifest.webmanifest", "/icon.svg",
"/icon-512.png"];

self.addEventListener("install", (event) => {
event.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)));
self.skipWaiting();
});

self.addEventListener("activate", (event) => {
event.waitUntil(
caches
.keys()
.then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
.then(() => self.clients.claim())
);
});

self.addEventListener("fetch", (event) => {
const url = new URL(event.request.url);
if (url.pathname.startsWith("/files/") || url.pathname.startsWith("/api/")) {
return;
}
if (url.pathname === "/ca-fingerprint" || url.pathname === "/ca.crt") {
return;
}
event.respondWith(
fetch(event.request)
.then((res) => {
const copy = res.clone();
caches.open(CACHE).then((c) => c.put(event.request, copy));
return res;
})
.catch(() => caches.match(event.request))
);
});
