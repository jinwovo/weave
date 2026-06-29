// The browser-side weave client: owns a CanvasDoc + HlcClock, applies local edits optimistically,
// sends them to the sync server, and merges everything it receives. It also models being offline:
// edits made while disconnected are applied locally and queued; on reconnect they flush and the
// client reconciles against the server's op-log, so the board converges — the CRDT payoff.

import { CanvasDoc, CanvasOp, Field, HlcClock, ShapeType, Timestamp, Vec2 } from './crdt';
import { opToWire, ServerMsg, textOpToWire, viewToState, WOp, wireToOp, wireToTextOp, WTextHistoryItem } from './protocol';
import { RgaText, TextOp } from './rga';

export type RemoteCursor = { x: number; y: number; tx: number; ty: number };
// A peer's in-progress draft (what they're drawing right now), rendered as a translucent preview.
export type RemoteDraft = { tool: string; a?: Vec2; b?: Vec2; pts?: Vec2[]; color: string };
export type Status = 'connecting' | 'open' | 'closed';

// Undo/redo is built on the CRDT itself: an op carries no implicit "previous" — so for every local
// edit we record an OpTemplate that *reverts* it and one that *re-applies* it (same shape, opposite
// effect), minus the timestamp. Undo stamps the inverse with a FRESH HLC and sends it as an ordinary
// op; because the doc is last-writer-wins, a fresh stamp always beats whatever is there — even if a
// peer touched the same field meanwhile. So undo is just "author the reverse edit now", which stays
// correct under concurrency instead of trying to rewind a shared log. Per-user stack: you undo your
// own actions, not everyone's. See docs/adr/0003-inverse-op-undo.md.
type OpTemplate =
  | { kind: 'CREATE'; shapeId: string; type: ShapeType; position: Vec2; size: Vec2; color: string; text: string; z: string }
  | { kind: 'SET'; shapeId: string; field: Field; vec?: Vec2; str?: string }
  | { kind: 'DELETE'; shapeId: string };

type UndoEntry = { undo: OpTemplate; redo: OpTemplate };
const MAX_HISTORY = 200;

export class WeaveClient {
  doc = new CanvasDoc();
  cursors = new Map<string, RemoteCursor>();
  drafts = new Map<string, RemoteDraft>(); // actor -> the shape they're currently drawing
  presence: string[] = [];
  status: Status = 'connecting';
  texts = new Map<string, RgaText>(); // shapeId -> RGA body (sticky / text shapes)
  onText: ((shapeId: string, op: TextOp) => void) | null = null;

  private clock = new HlcClock();
  private ws: WebSocket | null = null;
  private lastCursorSent = 0;
  private lastDraftSent = 0;
  private lastMoveSent = 0;
  private closedByUs = false;
  private offline = false;
  private connectedOnce = false;
  private outbox: unknown[] = []; // ops authored while disconnected, flushed on reconnect
  private undoStack: UndoEntry[] = [];
  private redoStack: UndoEntry[] = [];
  // captured at the start of a drag/resize gesture so the undo target is the pre-gesture value,
  // not whichever throttled mid-gesture frame happens to be in the doc when it ends.
  private gesture: { id: string; field: 'POSITION' | 'SIZE'; before: Vec2 } | null = null;

  constructor(
    public readonly room: string,
    public readonly actor: string,
    private readonly wsBase: string,
    private readonly onChange: () => void,
  ) {}

  connect(): void {
    const url = `${this.wsBase}/ws?room=${encodeURIComponent(this.room)}&actor=${encodeURIComponent(this.actor)}`;
    const ws = new WebSocket(url);
    this.ws = ws;
    this.status = 'connecting';
    this.onChange();
    ws.onopen = () => {
      this.status = 'open';
      this.flushOutbox();
      if (this.connectedOnce) void this.reconcile(); // catch up on edits missed while away
      this.connectedOnce = true;
      this.onChange();
    };
    ws.onclose = () => {
      this.status = 'closed';
      this.onChange();
      if (!this.closedByUs && !this.offline) setTimeout(() => this.connect(), 800);
    };
    ws.onmessage = (e) => this.onMessage(e.data as string);
  }

  close(): void {
    this.closedByUs = true;
    this.ws?.close();
  }

  // --- offline simulation (for the resilience demo) ---
  goOffline(): void {
    this.offline = true;
    this.ws?.close();
  }

  goOnline(): void {
    if (!this.offline) return;
    this.offline = false;
    this.connect();
  }

  isOffline(): boolean {
    return this.offline;
  }

  private flushOutbox(): void {
    const pending = this.outbox;
    this.outbox = [];
    for (const m of pending) this.ws?.send(JSON.stringify(m));
  }

  // Re-apply the full server op-log (real timestamps) so anything that happened while we were
  // offline merges correctly with our local edits — LWW by HLC, idempotent.
  private async reconcile(): Promise<void> {
    try {
      const ops = await this.fetchHistory();
      for (const w of ops) {
        const op = wireToOp(w);
        this.clock.receive(op.ts.hlc);
        this.doc.apply(op);
      }
      this.onChange();
    } catch {
      /* server momentarily unreachable; live messages will still flow */
    }
  }

  private onMessage(data: string): void {
    let msg: ServerMsg;
    try { msg = JSON.parse(data); } catch { return; }
    switch (msg.kind) {
      case 'snapshot':
        msg.shapes.forEach((v) => this.doc.mergeState(v.id, viewToState(v)));
        void this.loadText(); // rebuild per-shape RGAs from the text op-log
        this.onChange();
        break;
      case 'op': {
        const op = wireToOp(msg.op);
        this.clock.receive(op.ts.hlc);
        this.doc.apply(op);
        break;
      }
      case 'text': {
        const op = wireToTextOp(msg.op);
        this.rgaFor(msg.shapeId).apply(op);
        if (this.onText) this.onText(msg.shapeId, op);
        break;
      }
      case 'cursor': {
        if (msg.actor === this.actor) break;
        const cur = this.cursors.get(msg.actor);
        if (cur) { cur.tx = msg.x; cur.ty = msg.y; }
        else this.cursors.set(msg.actor, { x: msg.x, y: msg.y, tx: msg.x, ty: msg.y });
        break;
      }
      case 'draft': {
        if (msg.actor === this.actor) break;
        if (!msg.tool) { this.drafts.delete(msg.actor); break; } // they committed or cancelled
        this.drafts.set(msg.actor, {
          tool: msg.tool,
          a: msg.a ?? undefined,
          b: msg.b ?? undefined,
          pts: msg.pts ?? undefined,
          color: msg.color ?? '#94a3b8',
        });
        break;
      }
      case 'presence':
        this.presence = msg.actors;
        for (const a of [...this.cursors.keys()]) if (!msg.actors.includes(a)) this.cursors.delete(a);
        for (const a of [...this.drafts.keys()]) if (!msg.actors.includes(a)) this.drafts.delete(a);
        this.onChange();
        break;
    }
  }

  // Reliable = queued while offline; ephemeral (cursors) = dropped while offline.
  private sendReliable(obj: unknown): void {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(obj));
    else this.outbox.push(obj);
  }

  private sendEphemeral(obj: unknown): void {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(obj));
  }

  private stamp(): Timestamp {
    const h = this.clock.tick();
    return { hlc: { l: h.l, c: h.c }, actor: this.actor };
  }

  private localOp(op: CanvasOp): void {
    this.doc.apply(op);
    this.sendReliable({ kind: 'op', op: opToWire(op) });
  }

  // --- undo/redo (inverse ops) ---

  /** Stamp an OpTemplate with a fresh HLC, apply it locally, and broadcast it as a normal op. */
  private applyTemplate(t: OpTemplate): void {
    const ts = this.stamp();
    const op: CanvasOp =
      t.kind === 'CREATE' ? { kind: 'CREATE', shapeId: t.shapeId, ts, type: t.type, position: t.position, size: t.size, color: t.color, text: t.text, z: t.z }
      : t.kind === 'DELETE' ? { kind: 'DELETE', shapeId: t.shapeId, ts }
      : { kind: 'SET', shapeId: t.shapeId, ts, field: t.field, vec: t.vec, str: t.str };
    this.localOp(op);
  }

  /** Record an undoable edit; a fresh local edit invalidates the redo branch (standard editor semantics). */
  private record(entry: UndoEntry): void {
    this.undoStack.push(entry);
    if (this.undoStack.length > MAX_HISTORY) this.undoStack.shift();
    this.redoStack = [];
    this.onChange();
  }

  /** A CREATE template that reconstructs a shape's current state — the inverse of deleting it. */
  private restoreTemplate(id: string): OpTemplate | null {
    const s = this.doc.shapes.get(id);
    const type = s?.type?.value;
    const position = s?.position?.value;
    const size = s?.size?.value;
    if (!type || !position || !size) return null;
    return { kind: 'CREATE', shapeId: id, type, position, size, color: s!.color?.value ?? '#94a3b8', text: s!.text?.value ?? '', z: s!.z?.value ?? '0' };
  }

  canUndo(): boolean { return this.undoStack.length > 0; }
  canRedo(): boolean { return this.redoStack.length > 0; }

  undo(): void {
    const e = this.undoStack.pop();
    if (!e) return;
    this.applyTemplate(e.undo);
    this.redoStack.push(e);
    this.onChange();
  }

  redo(): void {
    const e = this.redoStack.pop();
    if (!e) return;
    this.applyTemplate(e.redo);
    this.undoStack.push(e);
    this.onChange();
  }

  // --- authoring ---

  create(type: ShapeType, position: Vec2, size: Vec2, color: string, text: string, z: string): string {
    const id = crypto.randomUUID();
    this.localOp({ kind: 'CREATE', shapeId: id, ts: this.stamp(), type, position, size, color, text, z });
    this.record({ undo: { kind: 'DELETE', shapeId: id }, redo: { kind: 'CREATE', shapeId: id, type, position, size, color, text, z } });
    return id;
  }

  setColor(id: string, color: string): void {
    const before = this.doc.shapes.get(id)?.color?.value;
    this.localOp({ kind: 'SET', shapeId: id, ts: this.stamp(), field: 'COLOR', str: color });
    this.record({ undo: { kind: 'SET', shapeId: id, field: 'COLOR', str: before ?? color }, redo: { kind: 'SET', shapeId: id, field: 'COLOR', str: color } });
  }

  setText(id: string, text: string): void {
    const before = this.doc.shapes.get(id)?.text?.value;
    this.localOp({ kind: 'SET', shapeId: id, ts: this.stamp(), field: 'TEXT', str: text });
    this.record({ undo: { kind: 'SET', shapeId: id, field: 'TEXT', str: before ?? '' }, redo: { kind: 'SET', shapeId: id, field: 'TEXT', str: text } });
  }

  remove(id: string): void {
    const restore = this.restoreTemplate(id); // capture the full shape BEFORE the tombstone lands
    this.localOp({ kind: 'DELETE', shapeId: id, ts: this.stamp() });
    if (restore) this.record({ undo: restore, redo: { kind: 'DELETE', shapeId: id } });
  }

  /** Snapshot the pre-gesture value once, on the first frame of a drag/resize. */
  private beginGesture(id: string, field: 'POSITION' | 'SIZE'): void {
    if (this.gesture && this.gesture.id === id && this.gesture.field === field) return;
    const s = this.doc.shapes.get(id);
    const cur = field === 'POSITION' ? s?.position?.value : s?.size?.value;
    if (cur) this.gesture = { id, field, before: { ...cur } };
  }

  /** Finalise a gesture: send the last op and record an undo back to the pre-gesture value. */
  private endGesture(id: string, field: 'POSITION' | 'SIZE', value: Vec2): void {
    const g = this.gesture;
    this.gesture = null;
    this.localOp({ kind: 'SET', shapeId: id, ts: this.stamp(), field, vec: value });
    const before = g && g.id === id && g.field === field ? g.before : undefined;
    if (before && (before.x !== value.x || before.y !== value.y)) {
      this.record({ undo: { kind: 'SET', shapeId: id, field, vec: before }, redo: { kind: 'SET', shapeId: id, field, vec: value } });
    }
  }

  /** Mid-drag: apply locally every frame for smoothness, throttle the network to ~30 fps. */
  drag(id: string, position: Vec2): void {
    this.beginGesture(id, 'POSITION');
    const op: CanvasOp = { kind: 'SET', shapeId: id, ts: this.stamp(), field: 'POSITION', vec: position };
    this.doc.apply(op);
    const now = Date.now();
    if (now - this.lastMoveSent > 33) {
      this.lastMoveSent = now;
      this.sendReliable({ kind: 'op', op: opToWire(op) });
    }
  }

  endDrag(id: string, position: Vec2): void {
    this.endGesture(id, 'POSITION', position);
  }

  /** Mid-resize: same throttling shape as drag. */
  resize(id: string, size: Vec2): void {
    this.beginGesture(id, 'SIZE');
    const op: CanvasOp = { kind: 'SET', shapeId: id, ts: this.stamp(), field: 'SIZE', vec: size };
    this.doc.apply(op);
    const now = Date.now();
    if (now - this.lastMoveSent > 33) {
      this.lastMoveSent = now;
      this.sendReliable({ kind: 'op', op: opToWire(op) });
    }
  }

  endResize(id: string, size: Vec2): void {
    this.endGesture(id, 'SIZE', size);
  }

  cursor(x: number, y: number): void {
    const now = Date.now();
    if (now - this.lastCursorSent < 40) return;
    this.lastCursorSent = now;
    this.sendEphemeral({ kind: 'cursor', x, y });
  }

  // --- live draft preview: broadcast the shape being drawn so peers see it form (ephemeral) ---

  /** A RECT/ELLIPSE/STICKY being dragged out, from corner a to corner b. Throttled like cursors. */
  draftShape(tool: string, a: Vec2, b: Vec2, color: string): void {
    const now = Date.now();
    if (now - this.lastDraftSent < 45) return;
    this.lastDraftSent = now;
    this.sendEphemeral({ kind: 'draft', tool, a, b, color });
  }

  /** A freehand stroke in progress; downsample to keep the ephemeral frame small. */
  draftPen(pts: Vec2[], color: string): void {
    const now = Date.now();
    if (now - this.lastDraftSent < 45) return;
    this.lastDraftSent = now;
    const step = pts.length > 120 ? Math.ceil(pts.length / 120) : 1;
    const thin = step === 1 ? pts : pts.filter((_, i) => i % step === 0 || i === pts.length - 1);
    this.sendEphemeral({ kind: 'draft', tool: 'PEN', pts: thin, color });
  }

  /** Tell peers to drop our preview — sent once when a draw commits or is cancelled (never throttled). */
  clearDraft(): void {
    this.sendEphemeral({ kind: 'draft', tool: null });
  }

  // --- collaborative text: an RGA sequence CRDT per shape body ---

  rgaFor(shapeId: string): RgaText {
    let r = this.texts.get(shapeId);
    if (!r) {
      r = new RgaText();
      this.texts.set(shapeId, r);
    }
    return r;
  }

  textOf(shapeId: string): string {
    return this.texts.get(shapeId)?.value() ?? '';
  }

  textInsert(shapeId: string, visIndex: number, ch: string): void {
    const op = this.rgaFor(shapeId).localInsert(visIndex, ch, this.stamp());
    // server's inbound envelope uses textShapeId/textOp (op/shapeId are taken by canvas ops)
    this.sendReliable({ kind: 'text', textShapeId: shapeId, textOp: textOpToWire(op) });
  }

  textDelete(shapeId: string, visIndex: number): void {
    const op = this.rgaFor(shapeId).localDelete(visIndex);
    this.sendReliable({ kind: 'text', textShapeId: shapeId, textOp: textOpToWire(op) });
  }

  private async loadText(): Promise<void> {
    try {
      const items = await this.fetchTextHistory();
      for (const it of items) this.rgaFor(it.shapeId).apply(wireToTextOp(it.op));
      this.onChange();
    } catch {
      /* server momentarily unreachable; live text ops still flow */
    }
  }

  async fetchTextHistory(): Promise<WTextHistoryItem[]> {
    const res = await fetch(`${this.httpBase()}/api/rooms/${encodeURIComponent(this.room)}/text`);
    if (!res.ok) throw new Error(`text fetch failed: ${res.status}`);
    return res.json();
  }

  private httpBase(): string {
    return this.wsBase.replace(/^ws/, 'http');
  }

  /** Fetch this room's full ordered op-log — powers time-travel replay and reconnect reconcile. */
  fetchHistory(): Promise<WOp[]> {
    return this.fetchHistoryFor(this.room);
  }

  async fetchHistoryFor(roomKey: string): Promise<WOp[]> {
    const res = await fetch(`${this.httpBase()}/api/rooms/${encodeURIComponent(roomKey)}/history`);
    if (!res.ok) throw new Error(`history fetch failed: ${res.status}`);
    return res.json();
  }

  /** List archived hourly buckets for a base room ("base@<hour>"), newest first. */
  async fetchEpochs(base: string): Promise<{ epoch: number; count: number }[]> {
    const res = await fetch(`${this.httpBase()}/api/rooms/${encodeURIComponent(base)}/epochs`);
    if (!res.ok) throw new Error(`epochs fetch failed: ${res.status}`);
    return res.json();
  }
}
