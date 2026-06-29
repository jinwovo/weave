// Capture + PROVE live presence: client B sees what client A is drawing *before A commits it* —
// the ephemeral draft preview — plus A's live cursor. Needs the full stack up (app :8103 + web :3009
// + postgres/redis), because drafts and cursors fan out through the WebSocket relay.
//
//   docker compose up -d && ./gradlew :app:bootRun       # :8103
//   cd web && npm run dev                                # :3009
//   cd scripts && node capture-presence.mjs
//
// Frames composite A|B side-by-side into docs/demo/weave-presence.gif. Exits non-zero on assertion
// failure, so it doubles as a regression test for the draft-broadcast path.

import { chromium } from 'playwright';
import gifenc from 'gifenc';
import pngjs from 'pngjs';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const { GIFEncoder, quantize, applyPalette } = gifenc;
const { PNG } = pngjs;

const URL = process.env.DEMO_URL || 'http://localhost:3009/?room=presence-gif';
const OUT_GIF = resolve(process.env.OUT_GIF || '../docs/demo/weave-presence.gif');
const PANE_W = 720;
const PANE_H = 460;
const GAP = 8;
const SCALE = 0.5;

let A, B;
const frames = [];
let OW = 0, OH = 0;

function readPng(buf) { const p = PNG.sync.read(buf); return { w: p.width, h: p.height, data: p.data }; }

function blit(out, ow, img, dx) {
  for (let y = 0; y < img.h; y++) {
    let si = y * img.w * 4, di = (y * ow + dx) * 4;
    for (let x = 0; x < img.w; x++) {
      out[di] = img.data[si]; out[di + 1] = img.data[si + 1]; out[di + 2] = img.data[si + 2]; out[di + 3] = 255;
      si += 4; di += 4;
    }
  }
}

function downscale(rgba, w, h, f) {
  const nw = Math.max(1, Math.floor(w * f)), nh = Math.max(1, Math.floor(h * f));
  const out = new Uint8Array(nw * nh * 4);
  for (let y = 0; y < nh; y++) {
    const sy = Math.floor(y / f);
    for (let x = 0; x < nw; x++) {
      const sx = Math.floor(x / f), si = (sy * w + sx) * 4, di = (y * nw + x) * 4;
      out[di] = rgba[si]; out[di + 1] = rgba[si + 1]; out[di + 2] = rgba[si + 2]; out[di + 3] = 255;
    }
  }
  return { data: out, w: nw, h: nh };
}

async function snap(times = 1) {
  const [ba, bb] = await Promise.all([A.screenshot(), B.screenshot()]);
  const ia = readPng(ba), ib = readPng(bb);
  const cw = ia.w + GAP + ib.w, ch = Math.max(ia.h, ib.h);
  const comp = new Uint8Array(cw * ch * 4);
  for (let i = 0; i < comp.length; i += 4) { comp[i] = 8; comp[i + 1] = 10; comp[i + 2] = 14; comp[i + 3] = 255; }
  blit(comp, cw, ia, 0);
  blit(comp, cw, ib, ia.w + GAP);
  const ds = downscale(comp, cw, ch, SCALE);
  OW = ds.w; OH = ds.h;
  for (let i = 0; i < times; i++) frames.push(ds.data);
}

// bright (drawn) pixels on a page's canvas — the dark board + faint grid stay under the threshold
function ink(page) {
  return page.evaluate(() => {
    const c = document.querySelector('canvas');
    const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data;
    let n = 0;
    for (let i = 0; i < d.length; i += 4) if (d[i] + d[i + 1] + d[i + 2] > 240) n++;
    return n;
  });
}

let failed = false;
function check(cond, msg) { console.log(`${cond ? 'OK ' : 'FAIL'}  ${msg}`); if (!cond) failed = true; }

async function main() {
  const browser = await chromium.launch();
  const mk = async () => (await browser.newContext({ viewport: { width: PANE_W, height: PANE_H } })).newPage();
  A = await mk();
  B = await mk();
  await A.goto(URL, { waitUntil: 'domcontentloaded' });
  await B.goto(URL, { waitUntil: 'domcontentloaded' });
  await A.waitForTimeout(2000); // connect + snapshot
  const kill = (p) => p.evaluate(() => document.querySelectorAll('nextjs-portal').forEach((e) => e.remove()));
  await Promise.all([kill(A), kill(B)]);

  const blankB = await ink(B);
  await snap(2);

  // --- A starts dragging a rectangle but does NOT release: B must see the live draft preview ---
  await A.click('button[title="rect"]');
  await A.mouse.move(150, 160);
  await A.mouse.down();
  await A.mouse.move(360, 320, { steps: 10 });
  await A.waitForTimeout(400); // let the draft frames fan out to B
  await snap(3);
  const draftB = await ink(B);
  check(draftB > blankB + 300, `B sees A's draft BEFORE commit (${blankB} -> ${draftB})`);

  // --- A releases: the shape commits; B still shows it (now a real shape, not a preview) ---
  await A.mouse.up();
  await A.waitForTimeout(400);
  await snap(3);
  const commitB = await ink(B);
  check(commitB > blankB + 300, `B still shows the shape after A commits (${commitB})`);

  // --- A draws a freehand stroke while holding: B watches the ink form live ---
  await A.click('button[title="pen"]');
  await A.mouse.move(430, 150);
  await A.mouse.down();
  for (const [x, y] of [[470, 200], [510, 150], [550, 210], [600, 150]]) {
    await A.mouse.move(x, y, { steps: 4 });
    await A.waitForTimeout(120);
    await snap();
  }
  await A.waitForTimeout(300);
  await snap(2);
  const penDraftB = await ink(B);
  check(penDraftB > commitB + 100, `B sees A's pen stroke mid-draw (${commitB} -> ${penDraftB})`);
  await A.mouse.up();
  await A.waitForTimeout(300);
  await snap(2);

  // --- A glides the cursor; B renders A's labelled cursor (a couple of frames to show motion).
  // (cursor updates fire on any pointer move, no tool switch needed — and the pen tool is up here) ---
  for (const [x, y] of [[520, 250], [380, 300], [250, 260], [180, 200]]) {
    await A.mouse.move(x, y, { steps: 5 });
    await snap();
  }
  await snap(3);

  await browser.close();

  mkdirSync(dirname(OUT_GIF), { recursive: true });
  const enc = GIFEncoder();
  for (const f of frames) {
    const palette = quantize(f, 256);
    enc.writeFrame(applyPalette(f, palette), OW, OH, { palette, delay: 150 });
  }
  enc.finish();
  writeFileSync(OUT_GIF, Buffer.from(enc.bytes()));
  console.log(`\ncaptured ${frames.length} frames @ ${OW}x${OH} -> ${OUT_GIF}`);

  if (failed) { console.error('\nASSERTIONS FAILED'); process.exit(1); }
  console.log('all presence (draft preview + cursor) assertions passed');
}

main().catch((e) => { console.error(e); process.exit(1); });
