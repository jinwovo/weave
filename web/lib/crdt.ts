// A faithful TypeScript port of the Java `crdt-core`. Running the SAME convergent type on the
// client lets edits apply optimistically (local-first) and still merge identically to the server
// and every other client. LWW by a globally-unique (HLC, actor) timestamp; merge = least upper
// bound, so it is commutative / associative / idempotent — convergence regardless of order.

export type Hlc = { l: number; c: number };

export function hlcCompare(a: Hlc, b: Hlc): number {
  if (a.l !== b.l) return a.l < b.l ? -1 : 1;
  if (a.c !== b.c) return a.c < b.c ? -1 : 1;
  return 0;
}

/** Per-actor Hybrid Logical Clock. */
export class HlcClock {
  private now: Hlc = { l: 0, c: 0 };
  constructor(private physical: () => number = () => Date.now()) {}

  tick(): Hlc {
    const pt = this.physical();
    const l = Math.max(this.now.l, pt);
    const c = l === this.now.l ? this.now.c + 1 : 0;
    this.now = { l, c };
    return this.now;
  }

  receive(remote: Hlc): Hlc {
    const pt = this.physical();
    const l = Math.max(this.now.l, remote.l, pt);
    let c: number;
    if (l === this.now.l && l === remote.l) c = Math.max(this.now.c, remote.c) + 1;
    else if (l === this.now.l) c = this.now.c + 1;
    else if (l === remote.l) c = remote.c + 1;
    else c = 0;
    this.now = { l, c };
    return this.now;
  }
}

export type Timestamp = { hlc: Hlc; actor: string };

export function tsCompare(a: Timestamp, b: Timestamp): number {
  const byHlc = hlcCompare(a.hlc, b.hlc);
  if (byHlc !== 0) return byHlc;
  return a.actor < b.actor ? -1 : a.actor > b.actor ? 1 : 0;
}

export type Reg<T> = { value: T; ts: Timestamp } | undefined;

function mergeReg<T>(a: Reg<T>, b: Reg<T>): Reg<T> {
  if (!a) return b;
  if (!b) return a;
  return tsCompare(a.ts, b.ts) >= 0 ? a : b;
}

export type ShapeType = 'RECT' | 'ELLIPSE' | 'STICKY' | 'TEXT' | 'ARROW' | 'PATH' | 'IMAGE';
export type Vec2 = { x: number; y: number };

export type ShapeState = {
  id: string;
  type: Reg<ShapeType>;
  position: Reg<Vec2>;
  size: Reg<Vec2>;
  color: Reg<string>;
  text: Reg<string>;
  z: Reg<string>;
  deleted: Reg<boolean>;
};

function emptyShape(id: string): ShapeState {
  return { id, type: undefined, position: undefined, size: undefined, color: undefined, text: undefined, z: undefined, deleted: undefined };
}

function mergeShape(a: ShapeState, b: ShapeState): ShapeState {
  return {
    id: a.id,
    type: mergeReg(a.type, b.type),
    position: mergeReg(a.position, b.position),
    size: mergeReg(a.size, b.size),
    color: mergeReg(a.color, b.color),
    text: mergeReg(a.text, b.text),
    z: mergeReg(a.z, b.z),
    deleted: mergeReg(a.deleted, b.deleted),
  };
}

export function isLive(s: ShapeState): boolean {
  return !!s.type && (!s.deleted || !s.deleted.value);
}

export type Field = 'POSITION' | 'SIZE' | 'COLOR' | 'TEXT' | 'Z';

export type CanvasOp =
  | { kind: 'CREATE'; shapeId: string; ts: Timestamp; type: ShapeType; position: Vec2; size: Vec2; color: string; text: string; z: string }
  | { kind: 'SET'; shapeId: string; ts: Timestamp; field: Field; vec?: Vec2; str?: string }
  | { kind: 'DELETE'; shapeId: string; ts: Timestamp };

function delta(op: CanvasOp): ShapeState {
  if (op.kind === 'CREATE') {
    return {
      id: op.shapeId,
      type: { value: op.type, ts: op.ts },
      position: { value: op.position, ts: op.ts },
      size: { value: op.size, ts: op.ts },
      color: { value: op.color, ts: op.ts },
      text: { value: op.text, ts: op.ts },
      z: { value: op.z, ts: op.ts },
      deleted: { value: false, ts: op.ts },
    };
  }
  const s = emptyShape(op.shapeId);
  if (op.kind === 'DELETE') return { ...s, deleted: { value: true, ts: op.ts } };
  switch (op.field) {
    case 'POSITION': return { ...s, position: { value: op.vec!, ts: op.ts } };
    case 'SIZE': return { ...s, size: { value: op.vec!, ts: op.ts } };
    case 'COLOR': return { ...s, color: { value: op.str!, ts: op.ts } };
    case 'TEXT': return { ...s, text: { value: op.str!, ts: op.ts } };
    case 'Z': return { ...s, z: { value: op.str!, ts: op.ts } };
  }
}

/** Mutable on the client (no need for the persistent structure the server uses); same semantics. */
export class CanvasDoc {
  shapes = new Map<string, ShapeState>();

  apply(op: CanvasOp): void {
    this.mergeState(op.shapeId, delta(op));
  }

  mergeState(id: string, s: ShapeState): void {
    const cur = this.shapes.get(id);
    this.shapes.set(id, cur ? mergeShape(cur, s) : s);
  }

  live(): ShapeState[] {
    return [...this.shapes.values()].filter(isLive).sort((a, b) => {
      const za = a.z?.value ?? '';
      const zb = b.z?.value ?? '';
      return za < zb ? -1 : za > zb ? 1 : 0;
    });
  }
}
