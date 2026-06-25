// The browser-side weave client: owns a CanvasDoc + HlcClock, applies local edits optimistically,
// sends them to the sync server, and merges everything it receives. It also models being offline:
// edits made while disconnected are applied locally and queued; on reconnect they flush and the
// client reconciles against the server's op-log, so the board converges — the CRDT payoff.

import { CanvasDoc, CanvasOp, HlcClock, ShapeType, Timestamp, Vec2 } from './crdt';
import { opToWire, ServerMsg, viewToState, WOp, wireToOp } from './protocol';

export type RemoteCursor = { x: number; y: number; tx: number; ty: number };
export type Status = 'connecting' | 'open' | 'closed';

export class WeaveClient {
  doc = new CanvasDoc();
  cursors = new Map<string, RemoteCursor>();
  presence: string[] = [];
  status: Status = 'connecting';

  private clock = new HlcClock();
  private ws: WebSocket | null = null;
  private lastCursorSent = 0;
  private lastMoveSent = 0;
  private closedByUs = false;
  private offline = false;
  private connectedOnce = false;
  private outbox: unknown[] = []; // ops authored while disconnected, flushed on reconnect

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
        this.onChange();
        break;
      case 'op': {
        const op = wireToOp(msg.op);
        this.clock.receive(op.ts.hlc);
        this.doc.apply(op);
        break;
      }
      case 'cursor': {
        if (msg.actor === this.actor) break;
        const cur = this.cursors.get(msg.actor);
        if (cur) { cur.tx = msg.x; cur.ty = msg.y; }
        else this.cursors.set(msg.actor, { x: msg.x, y: msg.y, tx: msg.x, ty: msg.y });
        break;
      }
      case 'presence':
        this.presence = msg.actors;
        for (const a of [...this.cursors.keys()]) if (!msg.actors.includes(a)) this.cursors.delete(a);
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

  // --- authoring ---

  create(type: ShapeType, position: Vec2, size: Vec2, color: string, text: string, z: string): string {
    const id = crypto.randomUUID();
    this.localOp({ kind: 'CREATE', shapeId: id, ts: this.stamp(), type, position, size, color, text, z });
    return id;
  }

  setColor(id: string, color: string): void {
    this.localOp({ kind: 'SET', shapeId: id, ts: this.stamp(), field: 'COLOR', str: color });
  }

  setText(id: string, text: string): void {
    this.localOp({ kind: 'SET', shapeId: id, ts: this.stamp(), field: 'TEXT', str: text });
  }

  remove(id: string): void {
    this.localOp({ kind: 'DELETE', shapeId: id, ts: this.stamp() });
  }

  /** Mid-drag: apply locally every frame for smoothness, throttle the network to ~30 fps. */
  drag(id: string, position: Vec2): void {
    const op: CanvasOp = { kind: 'SET', shapeId: id, ts: this.stamp(), field: 'POSITION', vec: position };
    this.doc.apply(op);
    const now = Date.now();
    if (now - this.lastMoveSent > 33) {
      this.lastMoveSent = now;
      this.sendReliable({ kind: 'op', op: opToWire(op) });
    }
  }

  endDrag(id: string, position: Vec2): void {
    this.localOp({ kind: 'SET', shapeId: id, ts: this.stamp(), field: 'POSITION', vec: position });
  }

  /** Mid-resize: same throttling shape as drag. */
  resize(id: string, size: Vec2): void {
    const op: CanvasOp = { kind: 'SET', shapeId: id, ts: this.stamp(), field: 'SIZE', vec: size };
    this.doc.apply(op);
    const now = Date.now();
    if (now - this.lastMoveSent > 33) {
      this.lastMoveSent = now;
      this.sendReliable({ kind: 'op', op: opToWire(op) });
    }
  }

  endResize(id: string, size: Vec2): void {
    this.localOp({ kind: 'SET', shapeId: id, ts: this.stamp(), field: 'SIZE', vec: size });
  }

  cursor(x: number, y: number): void {
    const now = Date.now();
    if (now - this.lastCursorSent < 40) return;
    this.lastCursorSent = now;
    this.sendEphemeral({ kind: 'cursor', x, y });
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
