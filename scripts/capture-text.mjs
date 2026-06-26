// Proof + demo of collaborative text: two browsers open the SAME sticky and type interleaved into it.
// A sequence CRDT (RGA) merges every character — both contributions survive and the two editors
// converge to the identical string. (Last-writer-wins could not do this; one side would be lost.)
// Asserts convergence, then writes a side-by-side GIF. Needs the sync server :8103 + web :3009 up.

import { chromium } from 'playwright';
import gifenc from 'gifenc';
import pngjs from 'pngjs';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const { GIFEncoder, quantize, applyPalette } = gifenc;
const { PNG } = pngjs;

const URL = process.env.DEMO_URL || `http://localhost:3009/?room=text-${Date.now()}`;
const OUT_GIF = resolve('../docs/demo/weave-text.gif');
const PANE_W = 720, PANE_H = 380, GAP = 8, SCALE = 0.5;

let A, B, OW = 0, OH = 0;
const frames = [];

const readPng = (buf) => { const p = PNG.sync.read(buf); return { w: p.width, h: p.height, data: p.data }; };
function blit(out, ow, img, dx) {
  for (let y = 0; y < img.h; y++) {
    let si = y * img.w * 4, di = (y * ow + dx) * 4;
    for (let x = 0; x < img.w; x++) { out[di] = img.data[si]; out[di+1] = img.data[si+1]; out[di+2] = img.data[si+2]; out[di+3] = 255; si += 4; di += 4; }
  }
}
function downscale(rgba, w, h, f) {
  const nw = Math.floor(w * f), nh = Math.floor(h * f), out = new Uint8Array(nw * nh * 4);
  for (let y = 0; y < nh; y++) { const sy = Math.floor(y / f); for (let x = 0; x < nw; x++) { const sx = Math.floor(x / f), si = (sy*w+sx)*4, di = (y*nw+x)*4; out[di]=rgba[si]; out[di+1]=rgba[si+1]; out[di+2]=rgba[si+2]; out[di+3]=255; } }
  return { data: out, w: nw, h: nh };
}
async function snap() {
  const [ba, bb] = await Promise.all([A.screenshot(), B.screenshot()]);
  const ia = readPng(ba), ib = readPng(bb), cw = ia.w + GAP + ib.w, ch = Math.max(ia.h, ib.h);
  const comp = new Uint8Array(cw * ch * 4);
  for (let i = 0; i < comp.length; i += 4) { comp[i]=8; comp[i+1]=10; comp[i+2]=14; comp[i+3]=255; }
  blit(comp, cw, ia, 0); blit(comp, cw, ib, ia.w + GAP);
  const ds = downscale(comp, cw, ch, SCALE); OW = ds.w; OH = ds.h; frames.push(ds.data);
}

async function main() {
  const browser = await chromium.launch();
  const mk = async () => (await browser.newContext({ viewport: { width: PANE_W, height: PANE_H } })).newPage();
  A = await mk(); B = await mk();
  await A.goto(URL, { waitUntil: 'networkidle' });
  await B.goto(URL, { waitUntil: 'networkidle' });
  await A.waitForTimeout(1500);
  const killOverlay = (p) => p.evaluate(() => document.querySelectorAll('nextjs-portal').forEach((e) => e.remove()));
  await Promise.all([killOverlay(A), killOverlay(B)]);

  // A drops a sticky (its editor opens, autofocused)
  await A.click('button[title="sticky"]', { force: true });
  await A.mouse.click(210, 150);
  await A.waitForTimeout(500);
  await snap();

  // B opens the same sticky's editor by double-clicking it
  await B.mouse.dblclick(230, 175);
  await B.waitForTimeout(400);
  await snap();

  // interleave typing into the SAME sticky from both browsers
  for (let i = 0; i < 4; i++) {
    await A.keyboard.type('A');
    await A.waitForTimeout(120);
    await snap();
    await B.keyboard.type('B');
    await B.waitForTimeout(120);
    await snap();
  }
  await A.waitForTimeout(900); // let the last ops settle on both sides
  await snap();
  await snap();

  const va = await A.$eval('.sticky-editor', (el) => el.value);
  const vb = await B.$eval('.sticky-editor', (el) => el.value);
  const as = (va.match(/A/g) || []).length, bs = (va.match(/B/g) || []).length;
  const converged = va === vb && va.length === 8 && as === 4 && bs === 4;
  console.log(`A="${va}"  B="${vb}"  converged=${converged} (As=${as} Bs=${bs})`);

  await browser.close();

  mkdirSync(dirname(OUT_GIF), { recursive: true });
  const enc = GIFEncoder();
  for (const f of frames) { const pal = quantize(f, 256); enc.writeFrame(applyPalette(f, pal), OW, OH, { palette: pal, delay: 150 }); }
  enc.finish();
  writeFileSync(OUT_GIF, Buffer.from(enc.bytes()));
  console.log(`captured ${frames.length} frames @ ${OW}x${OH} -> ${OUT_GIF}`);

  if (!converged) process.exit(1);
}

main().catch((e) => { console.error(e); process.exit(1); });
