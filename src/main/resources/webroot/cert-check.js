"use strict";

async function showCaFingerprint() {
const note = document.getElementById("ca-fingerprint-note");
const value = document.getElementById("ca-fingerprint");
if (!note || !value) return;
if (location.protocol !== "https:") return;
try {
const res = await fetch("/ca-fingerprint");
if (!res.ok) return;
const text = (await res.text()).trim();
if (!/^[0-9A-F]{2}(:[0-9A-F]{2}){31}$/.test(text)) return;
value.textContent = text;
note.hidden = false;
} catch {
}
}

showCaFingerprint();
