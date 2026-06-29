'use client';

import { useEffect, useRef, useState } from 'react';
import { CanvasDoc, ShapeState, ShapeType, Vec2 } from '@/lib/crdt';
import { WOp, wireToOp } from '@/lib/protocol';
import { colorFor, initialsFor, nameFor } from '@/lib/identity';
import { useWeave } from '@/lib/useWeave';

type Tool = 'select' | 'PEN' | 'RECT' | 'ELLIPSE' | 'STICKY' | 'ERASER';
type DrawTool = 'RECT' | 'ELLIPSE' | 'STICKY';

const PALETTE = ['#ef4444', '#f59e0b', '#eab308', '#22c55e', '#06b6d4', '#3b82f6', '#8b5cf6', '#f8fafc'];
const DEFAULT_SIZE: Record<DrawTool, Vec2> = {
  RECT: { x: 150, y: 96 },
  ELLIPSE: { x: 120, y: 120 },
  STICKY: { x: 190, y: 150 },
};
const TOOLS: Tool[] = ['select', 'PEN', 'RECT', 'ELLIPSE', 'STICKY', 'ERASER'];
const TOOL_ICON: Record<Tool, string> = { select: '⤧', PEN: '✏️', RECT: '▭', ELLIPSE: '◯', STICKY: '▢', ERASER: '🧽' };

function genActor(): string {
  return 'u' + Math.random().toString(36).slice(2, 8);
}

// The live board is bucketed by the hour (room "base@<hoursSinceEpoch>"), so it resets every hour
// while each past hour's op-log is preserved under its own room id and stays browsable.
const HOUR_MS = 3_600_000;
function currentEpoch(): number {
  return Math.floor(Date.now() / HOUR_MS);
}
function formatEpoch(ep: number): string {
  const d = new Date(ep * HOUR_MS);
  const hh = String(d.getHours()).padStart(2, '0');
  const nh = String((d.getHours() + 1) % 24).padStart(2, '0');
  return `${d.getMonth() + 1}/${d.getDate()} ${hh}:00–${nh}:00`;
}

function isResizable(type: ShapeType): boolean {
  return type === 'RECT' || type === 'ELLIPSE' || type === 'STICKY' || type === 'IMAGE';
}

function rectOf(a: Vec2, b: Vec2, type: DrawTool) {
  let x = Math.min(a.x, b.x);
  let y = Math.min(a.y, b.y);
  let w = Math.abs(b.x - a.x);
  let h = Math.abs(b.y - a.y);
  if (w < 8 && h < 8) {
    const d = DEFAULT_SIZE[type];
    w = d.x;
    h = d.y;
    x = a.x;
    y = a.y;
  }
  return { x, y, w, h };
}

// --- images: cache one HTMLImageElement per data URL; the rAF loop draws it once decoded ---
const imageCache = new Map<string, HTMLImageElement>();
function getImage(src: string | undefined): HTMLImageElement | null {
  if (!src) return null;
  let img = imageCache.get(src);
  if (!img) {
    img = new Image();
    img.src = src;
    imageCache.set(src, img);
  }
  return img;
}

function loadAndScale(file: File): Promise<{ dataUrl: string; w: number; h: number }> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error('read failed'));
    reader.onload = () => {
      const img = new Image();
      img.onload = () => {
        const max = 460;
        const scale = Math.min(1, max / Math.max(img.naturalWidth, img.naturalHeight));
        const w = Math.max(1, Math.round(img.naturalWidth * scale));
        const h = Math.max(1, Math.round(img.naturalHeight * scale));
        const c = document.createElement('canvas');
        c.width = w;
        c.height = h;
        const cx = c.getContext('2d')!;
        cx.fillStyle = '#ffffff';
        cx.fillRect(0, 0, w, h); // flatten any transparency before JPEG
        cx.drawImage(img, 0, 0, w, h);
        resolve({ dataUrl: c.toDataURL('image/jpeg', 0.85), w, h });
      };
      img.onerror = () => reject(new Error('decode failed'));
      img.src = reader.result as string;
    };
    reader.readAsDataURL(file);
  });
}

// --- canvas drawing (module scope so the rAF loop never closes over stale state) ---

function rr(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
  const rad = Math.max(0, Math.min(r, w / 2, h / 2));
  ctx.beginPath();
  ctx.moveTo(x + rad, y);
  ctx.arcTo(x + w, y, x + w, y + h, rad);
  ctx.arcTo(x + w, y + h, x, y + h, rad);
  ctx.arcTo(x, y + h, x, y, rad);
  ctx.arcTo(x, y, x + w, y, rad);
  ctx.closePath();
}

function drawText(ctx: CanvasRenderingContext2D, text: string, x: number, y: number, maxW: number, color: string) {
  if (!text) return;
  ctx.fillStyle = color;
  ctx.font = '15px ui-sans-serif, system-ui';
  ctx.textBaseline = 'alphabetic';
  const words = text.split(/\s+/);
  let line = '';
  let yy = y;
  for (const word of words) {
    const test = line ? line + ' ' + word : word;
    if (ctx.measureText(test).width > maxW && line) {
      ctx.fillText(line, x, yy);
      line = word;
      yy += 19;
    } else {
      line = test;
    }
  }
  if (line) ctx.fillText(line, x, yy);
}

// A freehand stroke is a PATH shape with its points (relative to top-left) serialised in `text`,
// so it flows through the existing op/CRDT/server path with no schema change.
function parsePath(text: string | undefined): Vec2[] | null {
  if (!text) return null;
  try {
    const a = JSON.parse(text);
    return Array.isArray(a) ? (a as Vec2[]) : null;
  } catch {
    return null;
  }
}

function strokePath(ctx: CanvasRenderingContext2D, pts: Vec2[], ox: number, oy: number, color: string) {
  if (pts.length === 0) return;
  ctx.strokeStyle = color;
  ctx.lineWidth = 3;
  ctx.lineJoin = 'round';
  ctx.lineCap = 'round';
  ctx.beginPath();
  ctx.moveTo(ox + pts[0].x, oy + pts[0].y);
  for (let i = 1; i < pts.length; i++) ctx.lineTo(ox + pts[i].x, oy + pts[i].y);
  ctx.stroke();
}

function drawShape(ctx: CanvasRenderingContext2D, s: ShapeState, selected: boolean, bodyText?: string) {
  const pos = s.position?.value;
  const sz = s.size?.value;
  const type = s.type?.value;
  if (!pos || !sz || !type) return;
  const color = s.color?.value ?? '#94a3b8';

  ctx.save();
  ctx.shadowColor = 'rgba(0,0,0,0.45)';
  ctx.shadowBlur = 18;
  ctx.shadowOffsetY = 7;
  ctx.fillStyle = color;
  if (type === 'RECT') {
    rr(ctx, pos.x, pos.y, sz.x, sz.y, 12);
    ctx.fill();
  } else if (type === 'ELLIPSE') {
    ctx.beginPath();
    ctx.ellipse(pos.x + sz.x / 2, pos.y + sz.y / 2, sz.x / 2, sz.y / 2, 0, 0, Math.PI * 2);
    ctx.fill();
  } else if (type === 'STICKY') {
    rr(ctx, pos.x, pos.y, sz.x, sz.y, 6);
    ctx.fill();
    ctx.shadowColor = 'transparent';
    drawText(ctx, bodyText ?? s.text?.value ?? '', pos.x + 14, pos.y + 28, sz.x - 28, '#1f2937');
  } else if (type === 'TEXT') {
    ctx.shadowColor = 'transparent';
    drawText(ctx, bodyText ?? s.text?.value ?? '', pos.x, pos.y + 18, sz.x, color);
  } else if (type === 'PATH') {
    const pts = parsePath(s.text?.value);
    if (pts) {
      ctx.shadowColor = 'transparent';
      strokePath(ctx, pts, pos.x, pos.y, color);
    }
  } else if (type === 'IMAGE') {
    ctx.fillStyle = '#ffffff';
    rr(ctx, pos.x, pos.y, sz.x, sz.y, 8);
    ctx.fill(); // white card casts the shadow
    ctx.shadowColor = 'transparent';
    ctx.save();
    rr(ctx, pos.x, pos.y, sz.x, sz.y, 8);
    ctx.clip();
    const img = getImage(s.text?.value);
    if (img && img.complete && img.naturalWidth > 0) {
      ctx.drawImage(img, pos.x, pos.y, sz.x, sz.y);
    } else {
      ctx.fillStyle = '#94a3b8';
      ctx.font = '12px ui-sans-serif, system-ui';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText('loading…', pos.x + sz.x / 2, pos.y + sz.y / 2);
      ctx.textAlign = 'left';
    }
    ctx.restore();
  }
  ctx.restore();

  if (selected) {
    ctx.save();
    ctx.strokeStyle = '#60a5fa';
    ctx.lineWidth = 2;
    ctx.setLineDash([6, 4]);
    rr(ctx, pos.x - 5, pos.y - 5, sz.x + 10, sz.y + 10, 14);
    ctx.stroke();
    ctx.restore();
    if (isResizable(type)) {
      ctx.save();
      ctx.fillStyle = '#60a5fa';
      ctx.strokeStyle = '#0b0d12';
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      ctx.rect(pos.x + sz.x - 5, pos.y + sz.y - 5, 10, 10);
      ctx.fill();
      ctx.stroke();
      ctx.restore();
    }
  }
}

function drawDraft(ctx: CanvasRenderingContext2D, type: DrawTool, a: Vec2, b: Vec2, color: string) {
  const { x, y, w, h } = rectOf(a, b, type);
  ctx.save();
  ctx.globalAlpha = 0.55;
  ctx.fillStyle = color;
  if (type === 'ELLIPSE') {
    ctx.beginPath();
    ctx.ellipse(x + w / 2, y + h / 2, w / 2, h / 2, 0, 0, Math.PI * 2);
    ctx.fill();
  } else {
    rr(ctx, x, y, w, h, type === 'STICKY' ? 6 : 12);
    ctx.fill();
  }
  ctx.restore();
}

function drawCursor(ctx: CanvasRenderingContext2D, x: number, y: number, color: string, name: string) {
  ctx.save();
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.moveTo(x, y);
  ctx.lineTo(x, y + 17);
  ctx.lineTo(x + 5, y + 12.5);
  ctx.lineTo(x + 12, y + 11.5);
  ctx.closePath();
  ctx.fill();
  ctx.font = '12px ui-sans-serif, system-ui';
  const w = ctx.measureText(name).width;
  rr(ctx, x + 11, y + 14, w + 14, 18, 9);
  ctx.fill();
  ctx.fillStyle = '#fff';
  ctx.textBaseline = 'alphabetic';
  ctx.fillText(name, x + 18, y + 27);
  ctx.restore();
}

function drawGrid(ctx: CanvasRenderingContext2D, w: number, h: number) {
  ctx.save();
  ctx.fillStyle = 'rgba(255,255,255,0.05)';
  const g = 28;
  for (let x = 0; x < w; x += g) {
    for (let y = 0; y < h; y += g) {
      ctx.beginPath();
      ctx.arc(x, y, 1, 0, Math.PI * 2);
      ctx.fill();
    }
  }
  ctx.restore();
}

type Travel = { ops: WOp[]; index: number; playing: boolean };

export default function Board({ room }: { room: string }) {
  const [actor] = useState(genActor);
  const [epoch, setEpoch] = useState(currentEpoch);
  useEffect(() => {
    const id = window.setInterval(() => setEpoch((prev) => (prev !== currentEpoch() ? currentEpoch() : prev)), 30_000);
    return () => clearInterval(id);
  }, []);
  const effectiveRoom = `${room}@${epoch}`;
  const { clientRef, status, presence } = useWeave(effectiveRoom, actor);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  const [tool, setTool] = useState<Tool>('select');
  const [color, setColor] = useState<string>('#eab308');
  const [selected, setSelected] = useState<string | null>(null);
  const [editing, setEditing] = useState<{ id: string; x: number; y: number; w: number; h: number; color: string } | null>(null);
  const [travel, setTravel] = useState<Travel | null>(null);
  const [offline, setOffline] = useState(false);
  const [archive, setArchive] = useState<{ epoch: number; count: number }[] | null>(null);
  const [copied, setCopied] = useState(false);

  const toolRef = useRef(tool); toolRef.current = tool;
  const colorRef = useRef(color); colorRef.current = color;
  const selectedRef = useRef(selected); selectedRef.current = selected;
  const draftRef = useRef<{ type: DrawTool; a: Vec2; b: Vec2 } | null>(null);
  const penRef = useRef<Vec2[] | null>(null);
  const dragRef = useRef<{ id: string; dx: number; dy: number } | null>(null);
  const resizeRef = useRef<{ id: string } | null>(null);
  const erasingRef = useRef(false);
  const travelDocRef = useRef<CanvasDoc | null>(null);
  const playRef = useRef<number | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);
  const editingRef = useRef(editing); editingRef.current = editing;

  const toCanvas = (e: { clientX: number; clientY: number }): Vec2 => {
    const r = canvasRef.current!.getBoundingClientRect();
    return { x: e.clientX - r.left, y: e.clientY - r.top };
  };

  const hit = (p: Vec2): ShapeState | null => {
    const live = clientRef.current?.doc.live() ?? [];
    for (let i = live.length - 1; i >= 0; i--) {
      const s = live[i];
      const pos = s.position?.value;
      const sz = s.size?.value;
      if (!pos || !sz) continue;
      if (p.x >= pos.x && p.x <= pos.x + sz.x && p.y >= pos.y && p.y <= pos.y + sz.y) return s;
    }
    return null;
  };

  const resizeHandleAt = (p: Vec2): string | null => {
    const id = selectedRef.current;
    if (!id) return null;
    const s = clientRef.current?.doc.shapes.get(id);
    const pos = s?.position?.value;
    const sz = s?.size?.value;
    const type = s?.type?.value;
    if (!pos || !sz || !type || !isResizable(type)) return null;
    if (Math.abs(p.x - (pos.x + sz.x)) <= 12 && Math.abs(p.y - (pos.y + sz.y)) <= 12) return id;
    return null;
  };

  // --- render loop ---
  useEffect(() => {
    const canvas = canvasRef.current!;
    const ctx = canvas.getContext('2d')!;
    let raf = 0;
    const resize = () => {
      const dpr = window.devicePixelRatio || 1;
      canvas.width = canvas.clientWidth * dpr;
      canvas.height = canvas.clientHeight * dpr;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    };
    resize();
    window.addEventListener('resize', resize);

    const loop = () => {
      const w = canvas.clientWidth;
      const h = canvas.clientHeight;
      ctx.clearRect(0, 0, w, h);
      drawGrid(ctx, w, h);
      const client = clientRef.current;
      const travelDoc = travelDocRef.current;
      const doc = travelDoc ?? client?.doc;
      if (doc) {
        for (const s of doc.live()) {
          const ty = s.type?.value;
          let body: string | undefined;
          if (!travelDoc && client && (ty === 'STICKY' || ty === 'TEXT')) {
            const t = client.textOf(s.id);
            if (t.length > 0) body = t;
          }
          drawShape(ctx, s, !travelDoc && s.id === selectedRef.current, body);
        }
      }
      if (!travelDoc && client) {
        const d = draftRef.current;
        if (d) drawDraft(ctx, d.type, d.a, d.b, colorRef.current);
        const pen = penRef.current;
        if (pen) {
          ctx.save();
          strokePath(ctx, pen, 0, 0, colorRef.current);
          ctx.restore();
        }
        // peers' in-progress drafts — watch what someone else is drawing before they commit it
        client.drafts.forEach((d, actor) => {
          ctx.save();
          if (d.tool === 'PEN' && d.pts && d.pts.length > 1) {
            ctx.globalAlpha = 0.75;
            strokePath(ctx, d.pts, 0, 0, d.color);
          } else if (d.a && d.b && (d.tool === 'RECT' || d.tool === 'ELLIPSE' || d.tool === 'STICKY')) {
            drawDraft(ctx, d.tool as DrawTool, d.a, d.b, d.color);
            const r = rectOf(d.a, d.b, d.tool as DrawTool); // dashed outline in the author's colour
            ctx.globalAlpha = 0.95;
            ctx.strokeStyle = colorFor(actor);
            ctx.lineWidth = 1.5;
            ctx.setLineDash([5, 4]);
            rr(ctx, r.x, r.y, r.w, r.h, 8);
            ctx.stroke();
          }
          ctx.restore();
        });
        client.cursors.forEach((c, id) => {
          c.x += (c.tx - c.x) * 0.25;
          c.y += (c.ty - c.y) * 0.25;
          drawCursor(ctx, c.x, c.y, colorFor(id), nameFor(id));
        });
      }
      raf = requestAnimationFrame(loop);
    };
    raf = requestAnimationFrame(loop);
    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener('resize', resize);
      if (playRef.current != null) clearInterval(playRef.current);
    };
  }, [clientRef]);

  // --- keyboard: undo/redo + delete selected ---
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const tag = (document.activeElement?.tagName ?? '').toLowerCase();
      if (tag === 'textarea' || tag === 'input') return; // let the sticky editor keep native undo
      const meta = e.ctrlKey || e.metaKey;
      if (meta && (e.key === 'z' || e.key === 'Z')) {
        e.preventDefault();
        if (travelDocRef.current) return;
        if (e.shiftKey) clientRef.current?.redo(); else clientRef.current?.undo();
        return;
      }
      if (meta && (e.key === 'y' || e.key === 'Y')) {
        e.preventDefault();
        if (!travelDocRef.current) clientRef.current?.redo();
        return;
      }
      if ((e.key === 'Delete' || e.key === 'Backspace') && selected && !travelDocRef.current) {
        clientRef.current?.remove(selected);
        setSelected(null);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [selected, clientRef]);

  // --- paste an image from the clipboard ---
  useEffect(() => {
    const onPaste = async (e: ClipboardEvent) => {
      if (travelDocRef.current) return;
      const items = e.clipboardData?.items;
      if (!items) return;
      for (let i = 0; i < items.length; i++) {
        if (items[i].type.startsWith('image/')) {
          const file = items[i].getAsFile();
          if (!file) continue;
          e.preventDefault();
          try {
            const { dataUrl, w, h } = await loadAndScale(file);
            const id = clientRef.current?.create('IMAGE', { x: window.innerWidth / 2 - w / 2, y: window.innerHeight / 2 - h / 2 }, { x: w, y: h }, '#ffffff', dataUrl, String(Date.now()));
            if (id) setSelected(id);
          } catch {
            /* ignore unreadable image */
          }
          break;
        }
      }
    };
    window.addEventListener('paste', onPaste);
    return () => window.removeEventListener('paste', onPaste);
  }, [clientRef]);

  const onDrop = async (e: React.DragEvent<HTMLCanvasElement>) => {
    e.preventDefault();
    if (travelDocRef.current) return;
    const file = e.dataTransfer?.files?.[0];
    if (!file || !file.type.startsWith('image/')) return;
    const p = toCanvas(e);
    try {
      const { dataUrl, w, h } = await loadAndScale(file);
      const id = clientRef.current?.create('IMAGE', { x: p.x - w / 2, y: p.y - h / 2 }, { x: w, y: h }, '#ffffff', dataUrl, String(Date.now()));
      if (id) setSelected(id);
    } catch {
      /* ignore */
    }
  };

  const eraseAt = (p: Vec2) => {
    const s = hit(p);
    if (s && s.type?.value !== 'IMAGE') {
      // images are intentionally immune to the eraser — remove them via select + delete only
      clientRef.current?.remove(s.id);
      if (selectedRef.current === s.id) setSelected(null);
    }
  };

  // --- pointer interaction (disabled while time-travelling) ---
  const onPointerDown = (e: React.PointerEvent<HTMLCanvasElement>) => {
    if (editing || travelDocRef.current) return;
    const p = toCanvas(e);
    const t = toolRef.current;
    if (t === 'ERASER') {
      erasingRef.current = true;
      eraseAt(p);
    } else if (t === 'select') {
      const rid = resizeHandleAt(p);
      if (rid) {
        resizeRef.current = { id: rid };
      } else {
        const s = hit(p);
        if (s) {
          const pos = s.position!.value;
          setSelected(s.id);
          dragRef.current = { id: s.id, dx: p.x - pos.x, dy: p.y - pos.y };
        } else {
          setSelected(null);
        }
      }
    } else if (t === 'PEN') {
      penRef.current = [p];
    } else {
      draftRef.current = { type: t, a: p, b: p };
    }
    canvasRef.current!.setPointerCapture(e.pointerId);
  };

  const onPointerMove = (e: React.PointerEvent<HTMLCanvasElement>) => {
    if (travelDocRef.current) return;
    const p = toCanvas(e);
    const client = clientRef.current;
    client?.cursor(p.x, p.y);
    if (erasingRef.current) {
      eraseAt(p);
    } else if (resizeRef.current && client) {
      const pos = client.doc.shapes.get(resizeRef.current.id)?.position?.value;
      if (pos) client.resize(resizeRef.current.id, { x: Math.max(20, p.x - pos.x), y: Math.max(20, p.y - pos.y) });
    } else if (penRef.current) {
      const pen = penRef.current;
      const last = pen[pen.length - 1];
      if (!last || Math.hypot(p.x - last.x, p.y - last.y) >= 2) pen.push(p);
      client?.draftPen(pen, colorRef.current);
    } else if (draftRef.current) {
      draftRef.current.b = p;
      client?.draftShape(draftRef.current.type, draftRef.current.a, p, colorRef.current);
    } else if (dragRef.current) {
      const dr = dragRef.current;
      client?.drag(dr.id, { x: p.x - dr.dx, y: p.y - dr.dy });
    }
  };

  const onPointerUp = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const client = clientRef.current;
    if (erasingRef.current) {
      erasingRef.current = false;
    } else if (resizeRef.current && client) {
      const sz = client.doc.shapes.get(resizeRef.current.id)?.size?.value;
      if (sz) client.endResize(resizeRef.current.id, sz);
      resizeRef.current = null;
    } else if (penRef.current && client) {
      const pen = penRef.current;
      penRef.current = null;
      client.clearDraft();
      if (pen.length > 0) {
        let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
        for (const pt of pen) {
          minX = Math.min(minX, pt.x);
          minY = Math.min(minY, pt.y);
          maxX = Math.max(maxX, pt.x);
          maxY = Math.max(maxY, pt.y);
        }
        const pad = 6;
        minX -= pad; minY -= pad; maxX += pad; maxY += pad;
        const rel = pen.map((pt) => ({ x: pt.x - minX, y: pt.y - minY }));
        client.create('PATH', { x: minX, y: minY }, { x: maxX - minX, y: maxY - minY }, colorRef.current, JSON.stringify(rel), String(Date.now()));
      }
    } else if (draftRef.current && client) {
      const d = draftRef.current;
      draftRef.current = null;
      client.clearDraft();
      const { x, y, w, h } = rectOf(d.a, d.b, d.type);
      const id = client.create(d.type, { x, y }, { x: w, y: h }, colorRef.current, '', String(Date.now()));
      setSelected(id);
      setTool('select');
      if (d.type === 'STICKY') {
        setEditing({ id, x, y, w, h, color: colorRef.current });
      }
    } else if (dragRef.current && client) {
      const dr = dragRef.current;
      const pos = client.doc.shapes.get(dr.id)?.position?.value;
      if (pos) client.endDrag(dr.id, pos);
      dragRef.current = null;
    }
    canvasRef.current?.releasePointerCapture(e.pointerId);
  };

  const onDoubleClick = (e: React.MouseEvent<HTMLCanvasElement>) => {
    if (travelDocRef.current) return;
    const p = toCanvas(e);
    const s = hit(p);
    if (!s) return;
    const type = s.type?.value;
    const pos = s.position?.value;
    const sz = s.size?.value;
    if ((type === 'STICKY' || type === 'TEXT') && pos && sz) {
      setSelected(s.id);
      setEditing({ id: s.id, x: pos.x, y: pos.y, w: sz.x, h: sz.y, color: s.color?.value ?? '#eab308' });
    }
  };

  const pickColor = (c: string) => {
    setColor(c);
    if (selected) clientRef.current?.setColor(selected, c);
  };

  // Turn an editor value-change into character-level RGA ops (handles typing, delete, paste, replace).
  const applyTextDiff = (shapeId: string, oldStr: string, newStr: string) => {
    const client = clientRef.current;
    if (!client) return;
    let p = 0;
    const min = Math.min(oldStr.length, newStr.length);
    while (p < min && oldStr[p] === newStr[p]) p++;
    let suf = 0;
    while (suf < oldStr.length - p && suf < newStr.length - p
        && oldStr[oldStr.length - 1 - suf] === newStr[newStr.length - 1 - suf]) suf++;
    const delCount = oldStr.length - p - suf;
    for (let k = 0; k < delCount; k++) client.textDelete(shapeId, p);
    const ins = newStr.slice(p, newStr.length - suf);
    for (let k = 0; k < ins.length; k++) client.textInsert(shapeId, p + k, ins[k]);
  };

  // Reconcile the open editor when a remote text op lands on the shape being edited (cursor-aware).
  useEffect(() => {
    const client = clientRef.current;
    if (!client) return;
    client.onText = (shapeId, op) => {
      const ed = editingRef.current;
      const ta = textareaRef.current;
      if (!ed || ed.id !== shapeId || !ta) return;
      const newVal = client.textOf(shapeId);
      if (ta.value === newVal) return; // our own echo or a no-op
      let pos = ta.selectionStart ?? newVal.length;
      const rga = client.rgaFor(shapeId);
      if (op.kind === 'INSERT') {
        if (rga.visibleIndexOf(op.id) <= pos) pos++;
      } else if (rga.visibleIndexOf(op.target) < pos) {
        pos--;
      }
      ta.value = newVal;
      const np = Math.max(0, Math.min(pos, newVal.length));
      ta.setSelectionRange(np, np);
    };
  });

  const commitEditing = () => setEditing(null);

  const toggleOffline = () => {
    const c = clientRef.current;
    if (!c) return;
    if (offline) { c.goOnline(); setOffline(false); }
    else { c.goOffline(); setOffline(true); }
  };

  const copyLink = async () => {
    try {
      await navigator.clipboard.writeText(window.location.href);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1400);
    } catch {
      /* clipboard blocked */
    }
  };

  // Render just the live shapes (no grid / cursors / UI chrome) to an offscreen canvas and download.
  const exportPng = () => {
    const client = clientRef.current;
    if (!client) return;
    const shapes = client.doc.live();
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    for (const s of shapes) {
      const p = s.position?.value;
      const z = s.size?.value;
      if (!p || !z) continue;
      minX = Math.min(minX, p.x);
      minY = Math.min(minY, p.y);
      maxX = Math.max(maxX, p.x + z.x);
      maxY = Math.max(maxY, p.y + z.y);
    }
    if (!isFinite(minX)) return;
    const pad = 48;
    const w = Math.ceil(maxX - minX + pad * 2);
    const h = Math.ceil(maxY - minY + pad * 2);
    const dpr = 2;
    const off = document.createElement('canvas');
    off.width = w * dpr;
    off.height = h * dpr;
    const ctx = off.getContext('2d');
    if (!ctx) return;
    ctx.scale(dpr, dpr);
    ctx.fillStyle = '#0f1115';
    ctx.fillRect(0, 0, w, h);
    ctx.translate(pad - minX, pad - minY);
    for (const s of shapes) drawShape(ctx, s, false);
    const a = document.createElement('a');
    a.href = off.toDataURL('image/png');
    a.download = `weave-${room}.png`;
    a.click();
  };

  // --- time travel ---
  const rebuildTravel = (ops: WOp[], index: number) => {
    const doc = new CanvasDoc();
    for (let i = 0; i < index; i++) doc.apply(wireToOp(ops[i]));
    travelDocRef.current = doc;
  };

  const stopPlay = () => {
    if (playRef.current != null) {
      clearInterval(playRef.current);
      playRef.current = null;
    }
  };

  const enterTravel = async () => {
    const client = clientRef.current;
    if (!client) return;
    try {
      const ops = await client.fetchHistory();
      rebuildTravel(ops, ops.length);
      setSelected(null);
      setTravel({ ops, index: ops.length, playing: false });
    } catch {
      /* stay live */
    }
  };

  const exitTravel = () => {
    stopPlay();
    travelDocRef.current = null;
    setTravel(null);
  };

  const scrub = (index: number) => {
    setTravel((t) => {
      if (!t) return t;
      stopPlay();
      rebuildTravel(t.ops, index);
      return { ...t, index, playing: false };
    });
  };

  const togglePlay = () => {
    setTravel((t) => {
      if (!t) return t;
      if (t.playing) {
        stopPlay();
        return { ...t, playing: false };
      }
      let idx = t.index >= t.ops.length ? 0 : t.index;
      const step = Math.max(1, Math.round(t.ops.length / 100));
      rebuildTravel(t.ops, idx);
      stopPlay();
      playRef.current = window.setInterval(() => {
        idx = Math.min(t.ops.length, idx + step);
        rebuildTravel(t.ops, idx);
        const playing = idx < t.ops.length;
        if (!playing) stopPlay();
        setTravel((cur) => (cur ? { ...cur, index: idx, playing } : cur));
      }, 30);
      return { ...t, index: idx, playing: true };
    });
  };

  // --- hourly archive ---
  const openArchive = async () => {
    const c = clientRef.current;
    if (!c) return;
    try { setArchive(await c.fetchEpochs(room)); } catch { setArchive([]); }
  };

  const loadArchive = async (ep: number) => {
    const c = clientRef.current;
    if (!c) return;
    try {
      const ops = await c.fetchHistoryFor(`${room}@${ep}`);
      rebuildTravel(ops, ops.length);
      setSelected(null);
      setTravel({ ops, index: ops.length, playing: false });
      setArchive(null);
    } catch {
      /* ignore */
    }
  };

  const roster = presence.includes(actor) ? presence : [actor, ...presence];

  return (
    <div className="board">
      <canvas
        ref={canvasRef}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onDoubleClick={onDoubleClick}
        onDrop={onDrop}
        onDragOver={(e) => e.preventDefault()}
      />

      <div className="topbar">
        <div className="brand">wea<span>ve</span></div>
        <div className={`dot ${offline ? 'closed' : status}`} title={offline ? 'offline' : status} />
        <div className="room">/{room}</div>
        <div className="room">· you are {nameFor(actor)}</div>
        <button className={`netbtn ${offline ? 'off' : ''}`} onClick={toggleOffline} title="simulate a network drop">
          {offline ? '🔌 go online' : '🌐 go offline'}
        </button>
        {travel && <div className="travel-tag">⟲ time travel — read only</div>}
      </div>

      <div className="presence">
        {roster.map((a) => (
          <div key={a} className="avatar" style={{ background: colorFor(a) }} title={nameFor(a)}>
            {initialsFor(a)}
          </div>
        ))}
      </div>

      {offline && <div className="offline-banner">⚡ OFFLINE — your edits are local · go online to merge them back</div>}
      {copied && <div className="toast">link copied ✓</div>}

      {!travel && (
        <div className="toolbar">
          {TOOLS.map((t) => (
            <button key={t} className={`tool ${tool === t ? 'active' : ''}`} onClick={() => setTool(t)} title={t.toLowerCase()}>
              {TOOL_ICON[t]}
            </button>
          ))}
          <div className="sep" />
          <div className="swatches">
            {PALETTE.map((c) => (
              <div key={c} className={`swatch ${color === c ? 'active' : ''}`} style={{ background: c }} onClick={() => pickColor(c)} />
            ))}
          </div>
          <div className="sep" />
          <button
            className="tool"
            title="undo (Ctrl/⌘+Z)"
            disabled={!clientRef.current?.canUndo()}
            onClick={() => clientRef.current?.undo()}
          >
            ↶
          </button>
          <button
            className="tool"
            title="redo (Ctrl/⌘+Shift+Z)"
            disabled={!clientRef.current?.canRedo()}
            onClick={() => clientRef.current?.redo()}
          >
            ↷
          </button>
          <div className="sep" />
          <button
            className="tool"
            title="delete selected"
            onClick={() => {
              if (selected) {
                clientRef.current?.remove(selected);
                setSelected(null);
              }
            }}
          >
            🗑
          </button>
          <button className="tool" title="time travel — replay this hour" onClick={enterTravel}>
            🕐
          </button>
          <button className="tool" title="archive — past hours" onClick={openArchive}>
            📚
          </button>
          <button className="tool" title="export PNG" onClick={exportPng}>
            📷
          </button>
          <button className="tool" title="copy room link" onClick={copyLink}>
            🔗
          </button>
        </div>
      )}

      {travel && (
        <div className="timeline">
          <button className="tool" title={travel.playing ? 'pause' : 'play'} onClick={togglePlay}>
            {travel.playing ? '⏸' : '▶'}
          </button>
          <input type="range" min={0} max={travel.ops.length} value={travel.index} onChange={(e) => scrub(Number(e.target.value))} />
          <div className="tcount">{travel.index} / {travel.ops.length} edits</div>
          <button className="tool live" title="back to live board" onClick={exitTravel}>● LIVE</button>
        </div>
      )}

      {!travel && <div className="hint">paste / drop an image · ✏️ sketch · drag for shapes · 🧽 erase · 🕐 replay · 📚 past hours · resets hourly</div>}

      {archive && (
        <div className="archive">
          <div className="archive-head">
            <span>past hours · /{room}</span>
            <button className="tool" title="close" onClick={() => setArchive(null)}>✕</button>
          </div>
          {archive.length === 0 && <div className="archive-empty">no recorded hours yet</div>}
          {archive.map((a) => {
            const isCurrent = a.epoch === epoch;
            return (
              <button
                key={a.epoch}
                className={`archive-item ${isCurrent ? 'current' : ''}`}
                onClick={() => {
                  if (isCurrent) {
                    setArchive(null);
                    exitTravel();
                  } else {
                    void loadArchive(a.epoch);
                  }
                }}
              >
                <span>{formatEpoch(a.epoch)}{isCurrent ? ' · live' : ''}</span>
                <span className="archive-count">{a.count}</span>
              </button>
            );
          })}
        </div>
      )}

      {editing && (
        <textarea
          key={editing.id}
          ref={textareaRef}
          className="sticky-editor"
          autoFocus
          defaultValue={clientRef.current?.textOf(editing.id) ?? ''}
          style={{ left: editing.x, top: editing.y, width: editing.w, height: editing.h, background: editing.color }}
          onInput={(e) => applyTextDiff(editing.id, clientRef.current?.textOf(editing.id) ?? '', e.currentTarget.value)}
          onBlur={commitEditing}
          onKeyDown={(e) => {
            if (e.key === 'Escape') e.currentTarget.blur();
          }}
        />
      )}
    </div>
  );
}
