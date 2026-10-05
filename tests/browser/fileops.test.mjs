/* The PWA file manager end to end: rename, delete (to the recycle
 * bin), restore and purge, new folder, and move — all through the real
 * dialogs, verified on disk. */
import { launchBrowser, CONTEXT_OPTIONS, BASE, SERVE_DIR } from "./lib.mjs";
import { writeFileSync, mkdirSync, existsSync, rmSync } from "node:fs";
import { join } from "node:path";

rmSync(join(SERVE_DIR, "ops"), { recursive: true, force: true });
mkdirSync(join(SERVE_DIR, "ops"), { recursive: true });
writeFileSync(join(SERVE_DIR, "ops", "victim.txt"), "delete me");
writeFileSync(join(SERVE_DIR, "ops", "old-name.txt"), "rename me");

const browser = await launchBrowser();
const page = await (await browser.newContext(CONTEXT_OPTIONS)).newPage();
await page.goto(BASE, { waitUntil: "networkidle" });

// Enter the ops/ directory
await page.click(`#server-list a:text("ops/")`);
await page.waitForSelector(`#server-list a:text("victim.txt")`);

let pass = 0;
let fail = 0;
const check = (label, cond) => {
  if (cond) { pass++; console.log(`PASS: ${label}`); }
  else { fail++; console.log(`FAIL: ${label}`); }
};

// Rename old-name.txt -> new-name.txt via the ✎ button's prompt()
page.once("dialog", (d) => d.accept("new-name.txt"));
await page.click(`#server-list li:has(a:text("old-name.txt")) button[title^="Rename"]`);
await page.waitForSelector(`#server-list a:text("new-name.txt")`, { timeout: 15000 });
check("renamed on disk", existsSync(join(SERVE_DIR, "ops", "new-name.txt"))
  && !existsSync(join(SERVE_DIR, "ops", "old-name.txt")));

// Delete victim.txt via the 🗑 button's confirm()
page.once("dialog", (d) => d.accept());
await page.click(`#server-list li:has(a:text("victim.txt")) button[title^="Delete"]`);
await page.waitForFunction(
  () => ![...document.querySelectorAll("#server-list a")].some((a) => a.textContent === "victim.txt"),
  { timeout: 15000 }
);
check("deleted on disk", !existsSync(join(SERVE_DIR, "ops", "victim.txt")));

// A failed delete says why: the file vanished from disk after listing.
writeFileSync(join(SERVE_DIR, "ops", "ghost.txt"), "gone soon");
await page.evaluate(() => loadDir(currentDir));
await page.waitForSelector(`#server-list a:text("ghost.txt")`);
rmSync(join(SERVE_DIR, "ops", "ghost.txt"));
const messages = [];
const onDialog = (d) => {
  messages.push(d.message());
  d.accept();
};
page.on("dialog", onDialog);
await page.click(`#server-list li:has(a:text("ghost.txt")) button[title^="Delete"]`);
for (let i = 0; i < 50 && messages.length < 2; i++) await page.waitForTimeout(100);
page.off("dialog", onDialog);
check("a failed delete shows an error message",
  messages.length === 2 && messages[1].includes("Could not delete \"ghost.txt\""));

// Recursive folder delete from the parent listing
await page.click(`#breadcrumbs a:text("files")`);
await page.waitForSelector(`#server-list a:text("ops/")`);
page.once("dialog", (d) => d.accept());
await page.click(`#server-list li:has(a:text("ops/")) button[title^="Delete"]`);
await page.waitForFunction(
  () => ![...document.querySelectorAll("#server-list a")].some((a) => a.textContent === "ops/"),
  { timeout: 15000 }
);
check("folder recursively deleted", !existsSync(join(SERVE_DIR, "ops")));

// New folder via the toolbar button
page.once("dialog", (d) => d.accept("made-here"));
await page.click("#new-folder");
await page.waitForSelector(`#server-list a:text("made-here/")`, { timeout: 15000 });
check("new folder created on disk", existsSync(join(SERVE_DIR, "made-here")));

// Move a root file into the new folder (⇄ prompt takes the target path)
writeFileSync(join(SERVE_DIR, "move-me.txt"), "moving");
await page.click(`#breadcrumbs a:text("files")`);
await page.waitForSelector(`#server-list a:text("move-me.txt")`);
page.once("dialog", (d) => d.accept("made-here"));
await page.click(`#server-list li:has(a:text("move-me.txt")) button[title^="Move"]`);
await page.waitForFunction(
  () => ![...document.querySelectorAll("#server-list a")]
    .some((a) => a.textContent === "move-me.txt"),
  { timeout: 15000 }
);
check("moved on disk", existsSync(join(SERVE_DIR, "made-here", "move-me.txt"))
  && !existsSync(join(SERVE_DIR, "move-me.txt")));

// The recycle bin holds the deleted items; restore the ops folder.
await page.click("#trash-button");
await page.waitForSelector("#trash-list li", { timeout: 15000 });
await page.click(`#trash-list li:has(.name:text("ops")) button[title^="Restore"]`);
await page.waitForSelector(`#server-list a:text("ops/")`, { timeout: 15000 });
check("folder restored from the bin",
  existsSync(join(SERVE_DIR, "ops")) && existsSync(join(SERVE_DIR, "ops", "new-name.txt")));

// Purge the deleted file forever.
page.once("dialog", (d) => d.accept());
await page.click(`#trash-list li:has(.name:text("victim.txt")) button[title^="Remove"]`);
await page.waitForFunction(
  () => ![...document.querySelectorAll("#trash-list .name")]
    .some((n) => n.textContent === "victim.txt"),
  { timeout: 15000 }
);
check("purged item stays gone", !existsSync(join(SERVE_DIR, "victim.txt"))
  && !existsSync(join(SERVE_DIR, "ops", "victim.txt")));

console.log(fail === 0 ? "TEST PASS" : "TEST FAIL");
await browser.close();
process.exit(fail === 0 ? 0 : 1);
