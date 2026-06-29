// The JSON protocol — mirrors the server's `Wire` records exactly — plus conversions to/from the
// in-memory CanvasOp. Keeping this in one file means the wire format has a single definition.

import { CanvasOp, Field, ShapeState, ShapeType, Timestamp } from './crdt';
import { TextOp } from './rga';

export type WTs = { l: number; c: number; actor: string };
export type WVec = { x: number; y: number };
export type WShape = { shapeType: string; position: WVec; size: WVec; color: string; text: string; z: string };
export type WOp = { shapeId: string; type: 'CREATE' | 'SET' | 'DELETE'; ts: WTs; shape?: WShape; field?: string; vec?: WVec; str?: string };

export type ShapeView = { id: string; shapeType: string; x: number; y: number; w: number; h: number; color: string; text: string; z: string };

export type WTextOp = { type: 'INSERT' | 'DELETE'; id?: WTs; origin?: WTs | null; ch?: string; target?: WTs };
export type WTextHistoryItem = { shapeId: string; op: WTextOp };

// An in-progress draft someone else is drawing (ephemeral, fanned out on the cursor channel).
// tool null = clear it; RECT/ELLIPSE/STICKY use a/b corners, PEN uses pts.
export type WDraft = { tool: string | null; a?: WVec | null; b?: WVec | null; pts?: WVec[] | null; color?: string | null };

export type ServerMsg =
  | { kind: 'snapshot'; shapes: ShapeView[] }
  | { kind: 'op'; op: WOp }
  | { kind: 'cursor'; actor: string; x: number; y: number }
  | { kind: 'presence'; actors: string[] }
  | { kind: 'text'; shapeId: string; op: WTextOp }
  | ({ kind: 'draft'; actor: string } & WDraft);

function tsToWire(ts: Timestamp): WTs {
  return { l: ts.hlc.l, c: ts.hlc.c, actor: ts.actor };
}

function wireToTs(w: WTs): Timestamp {
  return { hlc: { l: w.l, c: w.c }, actor: w.actor };
}

export function textOpToWire(op: TextOp): WTextOp {
  if (op.kind === 'INSERT') {
    return { type: 'INSERT', id: tsToWire(op.id), origin: op.originLeft ? tsToWire(op.originLeft) : null, ch: op.value };
  }
  return { type: 'DELETE', target: tsToWire(op.target) };
}

export function wireToTextOp(w: WTextOp): TextOp {
  if (w.type === 'INSERT') {
    return { kind: 'INSERT', id: wireToTs(w.id!), originLeft: w.origin ? wireToTs(w.origin) : null, value: w.ch! };
  }
  return { kind: 'DELETE', target: wireToTs(w.target!) };
}

export function opToWire(op: CanvasOp): WOp {
  const ts: WTs = { l: op.ts.hlc.l, c: op.ts.hlc.c, actor: op.ts.actor };
  if (op.kind === 'CREATE') {
    return { shapeId: op.shapeId, type: 'CREATE', ts, shape: { shapeType: op.type, position: op.position, size: op.size, color: op.color, text: op.text, z: op.z } };
  }
  if (op.kind === 'DELETE') {
    return { shapeId: op.shapeId, type: 'DELETE', ts };
  }
  const w: WOp = { shapeId: op.shapeId, type: 'SET', ts, field: op.field };
  if (op.field === 'POSITION' || op.field === 'SIZE') w.vec = op.vec;
  else w.str = op.str;
  return w;
}

export function wireToOp(w: WOp): CanvasOp {
  const ts: Timestamp = { hlc: { l: w.ts.l, c: w.ts.c }, actor: w.ts.actor };
  if (w.type === 'CREATE') {
    const s = w.shape!;
    return { kind: 'CREATE', shapeId: w.shapeId, ts, type: s.shapeType as ShapeType, position: s.position, size: s.size, color: s.color, text: s.text, z: s.z };
  }
  if (w.type === 'DELETE') {
    return { kind: 'DELETE', shapeId: w.shapeId, ts };
  }
  return { kind: 'SET', shapeId: w.shapeId, ts, field: w.field as Field, vec: w.vec, str: w.str };
}

// A snapshot carries flattened values, not timestamps. We seed them at the semilattice bottom
// (ts = 0) so any real subsequent edit — local or remote — always wins. Correct because the
// server only streams ops that are causally AFTER the snapshot, hence with higher HLCs.
const BASE_TS: Timestamp = { hlc: { l: 0, c: 0 }, actor: '' };

export function viewToState(v: ShapeView): ShapeState {
  return {
    id: v.id,
    type: { value: v.shapeType as ShapeType, ts: BASE_TS },
    position: { value: { x: v.x, y: v.y }, ts: BASE_TS },
    size: { value: { x: v.w, y: v.h }, ts: BASE_TS },
    color: v.color != null ? { value: v.color, ts: BASE_TS } : undefined,
    text: v.text != null ? { value: v.text, ts: BASE_TS } : undefined,
    z: v.z != null ? { value: v.z, ts: BASE_TS } : undefined,
    deleted: { value: false, ts: BASE_TS },
  };
}
