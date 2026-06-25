// Capture the two-client multiplayer demo: two browser contexts join the same room; client A draws
// while client B watches it converge live (shapes appear, A's cursor glides). Frames are composited
// side-by-side and encoded to a GIF with gifenc — no ffmpeg. Also writes a poster PNG.
//
//   (start the sync server :8103 and the web client :3009 first)
//   cd scripts && npm install && npx playwright install chromium && node capture-demo.mjs

import { chromium } from 'playwright';
import gifenc from 'gifenc';
import pngjs from 'pngjs';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const { GIFEncoder, quantize, applyPalette } = gifenc;
const { PNG } = pngjs;

const URL = process.env.DEMO_URL || 'http://localhost:3009/?room=demo-gif';
const OUT_GIF = resolve(process.env.OUT_GIF || '../docs/demo/weave.gif');
const OUT_PNG = resolve(process.env.OUT_PNG || '../docs/demo/weave.png');
const PANE_W = 760;
const PANE_H = 460;
const GAP = 8;
const SCALE = 0.5; // downscale the composite to keep the GIF small

let A;
let B;
const frames = [];
let OW = 0;
let OH = 0;

function readPng(buf) {
  const p = PNG.sync.read(buf);
  return { w: p.width, h: p.height, data: p.data };
}

function blit(out, ow, img, dx) {
  for (let y = 0; y < img.h; y++) {
    let si = y * img.w * 4;
    let di = (y * ow + dx) * 4;
    for (let x = 0; x < img.w; x++) {
      out[di] = img.data[si];
      out[di + 1] = img.data[si + 1];
      out[di + 2] = img.data[si + 2];
      out[di + 3] = 255;
      si += 4;
      di += 4;
    }
  }
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

async function snap() {
  const [ba, bb] = await Promise.all([A.screenshot(), B.screenshot()]);
  const ia = readPng(ba);
  const ib = readPng(bb);
  const cw = ia.w + GAP + ib.w;
  const ch = Math.max(ia.h, ib.h);
  const comp = new Uint8Array(cw * ch * 4);
  for (let i = 0; i < comp.length; i += 4) {
    comp[i] = 8;
    comp[i + 1] = 10;
    comp[i + 2] = 14;
    comp[i + 3] = 255;
  }
  blit(comp, cw, ia, 0);
  blit(comp, cw, ib, ia.w + GAP);
  const ds = downscale(comp, cw, ch, SCALE);
  OW = ds.w;
  OH = ds.h;
  frames.push(ds.data);
}

async function main() {
  const browser = await chromium.launch();
  const mkPage = async () => {
    const c = await browser.newContext({ viewport: { width: PANE_W, height: PANE_H } });
    return c.newPage();
  };
  A = await mkPage();
  B = await mkPage();
  await A.goto(URL, { waitUntil: 'networkidle' });
  await B.goto(URL, { waitUntil: 'networkidle' });
  await A.waitForTimeout(1600); // connect + snapshot

  // the Next.js dev overlay (<nextjs-portal>) is a full-viewport layer that intercepts pointer
  // events — remove it on both pages so tool clicks and canvas drags land on the board.
  const killOverlay = (p) => p.evaluate(() => document.querySelectorAll('nextjs-portal').forEach((e) => e.remove()));
  await Promise.all([killOverlay(A), killOverlay(B)]);

  const tool = async (t) => {
    await A.click(`button[title="${t}"]`);
  };
  const settle = (ms = 160) => A.waitForTimeout(ms);

  await snap();

  // 1) rectangle
  await tool('rect');
  await A.mouse.move(110, 130);
  await snap();
  await A.mouse.down();
  await A.mouse.move(210, 205, { steps: 6 });
  await snap();
  await A.mouse.up();
  await settle();
  await snap();

  // 2) ellipse
  await tool('ellipse');
  await A.mouse.move(270, 130);
  await snap();
  await A.mouse.down();
  await A.mouse.move(360, 220, { steps: 6 });
  await snap();
  await A.mouse.up();
  await settle();
  await snap();

  // 3) freehand pen squiggle
  await tool('pen');
  await A.mouse.move(420, 150);
  await A.mouse.down();
  for (const [x, y] of [[450, 190], [480, 150], [510, 195], [540, 150], [570, 190]]) {
    await A.mouse.move(x, y, { steps: 3 });
    await snap();
  }
  await A.mouse.up();
  await settle();
  await snap();

  // 4) move the cursor around — B sees A's cursor glide
  await tool('select');
  for (const [x, y] of [[520, 250], [430, 280], [320, 250], [220, 285], [150, 250], [120, 175]]) {
    await A.mouse.move(x, y, { steps: 4 });
    await snap();
  }
  await snap();
  await snap();

  await browser.close();

  mkdirSync(dirname(OUT_GIF), { recursive: true });

  const enc = GIFEncoder();
  for (const f of frames) {
    const palette = quantize(f, 256);
    const index = applyPalette(f, palette);
    enc.writeFrame(index, OW, OH, { palette, delay: 140 });
  }
  enc.finish();
  writeFileSync(OUT_GIF, Buffer.from(enc.bytes()));

  const last = frames[frames.length - 1];
  const png = new PNG({ width: OW, height: OH });
  png.data = Buffer.from(last);
  writeFileSync(OUT_PNG, PNG.sync.write(png));

  console.log(`captured ${frames.length} frames @ ${OW}x${OH} -> ${OUT_GIF} + ${OUT_PNG}`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
