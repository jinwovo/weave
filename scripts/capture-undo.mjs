// Capture + PROVE undo/redo. Undo/redo is pure client-side CRDT logic (each local edit records an
// inverse op that is re-authored with a fresh HLC on undo), so it needs only the web client — no
// sync server, no Docker. We draw three shapes, then drive the toolbar's ↶/↷ buttons and assert,
// through the rendered canvas alone, that undo erases the board and redo brings it back. The frames
// are encoded to a GIF with gifenc (no ffmpeg).
//
//   cd web && npm run dev          # :3009 (the server may be down; local edits still apply)
//   cd scripts && node capture-undo.mjs
//
// Exits non-zero if any assertion fails, so it doubles as a regression test.

import { chromium } from 'playwright';
import gifenc from 'gifenc';
import pngjs from 'pngjs';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const { GIFEncoder, quantize, applyPalette } = gifenc;
const { PNG } = pngjs;

const URL = process.env.DEMO_URL || 'http://localhost:3009/?room=undo-gif';
const OUT_GIF = resolve(process.env.OUT_GIF || '../docs/demo/weave-undo.gif');
const PANE_W = 820;
const PANE_H = 480;
const SCALE = 0.6;

const frames = [];
let OW = 0;
let OH = 0;
let page;

function readPng(buf) {
  const p = PNG.sync.read(buf);
  return { w: p.width, h: p.height, data: p.data };
}

function downscale(rgba, w, h, factor) {
  const nw = Math.max(1, Math.floor(w * factor));
  const nh = Math.max(1, Math.floor(h * factor));
  const out = new Uint8Array(nw * nh * 4);
  for (let y = 0; y < nh; y++) {
    const sy = Math.floor(y / factor);
    for (let x = 0; x < nw; x++) {
      const sx = Math.floor(x / factor);
      const si = (sy * w + sx) * 4;
      const di = (y * nw + x) * 4;
      out[di] = rgba[si];
      out[di + 1] = rgba[si + 1];
      out[di + 2] = rgba[si + 2];
      out[di + 3] = 255;
    }
  }
  return { data: out, w: nw, h: nh };
}

async function snap(times = 1) {
  const buf = await page.screenshot();
  const img = readPng(buf);
  const ds = downscale(img.data, img.w, img.h, SCALE);
  OW = ds.w;
  OH = ds.h;
  for (let i = 0; i < times; i++) frames.push(ds.data);
}

// "Ink" = count of bright (shape) pixels on the canvas. The dark board + faint grid dots stay well
// under the threshold, so this cleanly measures how much drawn content is on screen.
function ink() {
  return page.evaluate(() => {
    const c = document.querySelector('canvas');
    const ctx = c.getContext('2d');
    const d = ctx.getImageData(0, 0, c.width, c.height).data;
    let n = 0;
    for (let i = 0; i < d.length; i += 4) {
      if (d[i] + d[i + 1] + d[i + 2] > 240) n++;
    }
    return n;
  });
}

const UNDO = 'button[title^="undo"]';
const REDO = 'button[title^="redo"]';
const settle = (ms = 220) => page.waitForTimeout(ms);

let failed = false;
function check(cond, msg) {
  console.log(`${cond ? 'OK ' : 'FAIL'}  ${msg}`);
  if (!cond) failed = true;
}

async function drawRect(x1, y1, x2, y2) {
  await page.click('button[title="rect"]');
  await page.mouse.move(x1, y1);
  await page.mouse.down();
  await page.mouse.move(x2, y2, { steps: 6 });
  await page.mouse.up();
  await settle();
}

async function main() {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: PANE_W, height: PANE_H } });
  page = await ctx.newPage();
  await page.goto(URL, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1500); // mount + (attempt to) connect; local edits work regardless
  await page.evaluate(() => document.querySelectorAll('nextjs-portal').forEach((e) => e.remove()));

  await snap(3);
  const blank = await ink();
  check(await page.locator(UNDO).isDisabled(), 'undo disabled on a fresh board');

  // --- draw three shapes, snapshotting ink after each ---
  await drawRect(120, 150, 250, 250);
  await snap(2);
  const ink1 = await ink();
  await drawRect(300, 150, 430, 250);
  await snap(2);
  const ink2 = await ink();
  await drawRect(480, 150, 610, 250);
  await snap(3);
  const ink3 = await ink();

  check(ink1 > blank + 200, `shape 1 adds ink (${blank} -> ${ink1})`);
  check(ink2 > ink1 + 200, `shape 2 adds ink (${ink1} -> ${ink2})`);
  check(ink3 > ink2 + 200, `shape 3 adds ink (${ink2} -> ${ink3})`);
  check(!(await page.locator(UNDO).isDisabled()), 'undo enabled after drawing');
  check(await page.locator(REDO).isDisabled(), 'redo disabled (no undo yet)');

  // --- undo all three: board returns to blank, monotonically losing ink ---
  await page.click(UNDO);
  await snap(2);
  const u2 = await ink();
  await page.click(UNDO);
  await snap(2);
  const u1 = await ink();
  await page.click(UNDO);
  await snap(3);
  const u0 = await ink();

  check(u2 < ink3 - 200, `undo 1 removes a shape (${ink3} -> ${u2})`);
  check(u1 < u2 - 200, `undo 2 removes a shape (${u2} -> ${u1})`);
  check(Math.abs(u0 - blank) < 150, `undo 3 returns to blank (${u0} ~= ${blank})`);
  check(await page.locator(UNDO).isDisabled(), 'undo disabled again at the bottom of the stack');
  check(!(await page.locator(REDO).isDisabled()), 'redo enabled after undoing');

  // --- redo all three: every shape comes back, ending exactly where we were ---
  await page.click(REDO);
  await snap(2);
  const r1 = await ink();
  await page.click(REDO);
  await snap(2);
  const r2 = await ink();
  await page.click(REDO);
  await snap(3);
  const r3 = await ink();

  check(r1 > u0 + 200, `redo 1 restores a shape (${u0} -> ${r1})`);
  check(r2 > r1 + 200, `redo 2 restores a shape (${r1} -> ${r2})`);
  check(Math.abs(r3 - ink3) < 150, `redo 3 restores the full board (${r3} ~= ${ink3})`);
  check(await page.locator(REDO).isDisabled(), 'redo disabled again at the top of the stack');

  await browser.close();

  mkdirSync(dirname(OUT_GIF), { recursive: true });
  const enc = GIFEncoder();
  for (const f of frames) {
    const palette = quantize(f, 256);
    const index = applyPalette(f, palette);
    enc.writeFrame(index, OW, OH, { palette, delay: 320 });
  }
  enc.finish();
  writeFileSync(OUT_GIF, Buffer.from(enc.bytes()));
  console.log(`\ncaptured ${frames.length} frames @ ${OW}x${OH} -> ${OUT_GIF}`);

  if (failed) {
    console.error('\nASSERTIONS FAILED');
    process.exit(1);
  }
  console.log('all undo/redo assertions passed');
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
