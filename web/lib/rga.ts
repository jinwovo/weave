// A TypeScript port of crdt-core/RgaText — the sequence CRDT that backs collaboratively-edited shape
// bodies. Same algorithm as the Java side (and the same one the property/DST tests prove): unique HLC
// id per character, concurrent inserts ordered by id (greatest first), tombstone deletes, and ops
// buffered until their dependency arrives so it converges in any delivery order.

import { Timestamp, tsCompare } from './crdt';

export type TextInsert = { kind: 'INSERT'; id: Timestamp; originLeft: Timestamp | null; value: string };
export type TextDelete = { kind: 'DELETE'; target: Timestamp };
export type TextOp = TextInsert | TextDelete;

type Node = { id: Timestamp; key: string; value: string; deleted: boolean };

function keyOf(ts: Timestamp): string {
  return `${ts.hlc.l}:${ts.hlc.c}:${ts.actor}`;
}

export class RgaText {
  private nodes: Node[] = [];
  private byKey = new Map<string, Node>();
  private pending: TextOp[] = [];

  apply(op: TextOp): void {
    if (this.tryApply(op)) {
      this.drain();
    } else {
      this.pending.push(op);
    }
  }

  private tryApply(op: TextOp): boolean {
    if (op.kind === 'INSERT') {
      if (this.byKey.has(keyOf(op.id))) return true; // idempotent
      if (op.originLeft && !this.byKey.has(keyOf(op.originLeft))) return false; // dependency missing
      this.insert(op);
      return true;
    }
    const n = this.byKey.get(keyOf(op.target));
    if (!n) return false; // target not here yet
    n.deleted = true;
    return true;
  }

  private insert(op: TextInsert): void {
    let i = op.originLeft ? this.indexOfKey(keyOf(op.originLeft)) + 1 : 0;
    while (i < this.nodes.length && tsCompare(this.nodes[i].id, op.id) > 0) i++;
    const node: Node = { id: op.id, key: keyOf(op.id), value: op.value, deleted: false };
    this.nodes.splice(i, 0, node);
    this.byKey.set(node.key, node);
  }

  private drain(): void {
    let progress = true;
    while (progress) {
      progress = false;
      for (let i = this.pending.length - 1; i >= 0; i--) {
        if (this.tryApply(this.pending[i])) {
          this.pending.splice(i, 1);
          progress = true;
        }
      }
    }
  }

  private indexOfKey(key: string): number {
    for (let i = 0; i < this.nodes.length; i++) if (this.nodes[i].key === key) return i;
    return -1;
  }

  value(): string {
    let s = '';
    for (const n of this.nodes) if (!n.deleted) s += n.value;
    return s;
  }

  length(): number {
    let n = 0;
    for (const node of this.nodes) if (!node.deleted) n++;
    return n;
  }

  private visibleId(visIndex: number): Timestamp {
    let seen = 0;
    for (const n of this.nodes) {
      if (n.deleted) continue;
      if (seen === visIndex) return n.id;
      seen++;
    }
    throw new RangeError(`visible index ${visIndex} of ${this.length()}`);
  }

  /** Visible position of an element by id: the number of non-deleted nodes before it (for cursor math). */
  visibleIndexOf(id: Timestamp): number {
    const key = keyOf(id);
    let seen = 0;
    for (const n of this.nodes) {
      if (n.key === key) return seen;
      if (!n.deleted) seen++;
    }
    return seen;
  }

  // --- local authoring (the caller stamps the id with its own HLC + actor) ---

  localInsert(visIndex: number, value: string, id: Timestamp): TextInsert {
    const originLeft = visIndex === 0 ? null : this.visibleId(visIndex - 1);
    const op: TextInsert = { kind: 'INSERT', id, originLeft, value };
    this.apply(op);
    return op;
  }

  localDelete(visIndex: number): TextDelete {
    const op: TextDelete = { kind: 'DELETE', target: this.visibleId(visIndex) };
    this.apply(op);
    return op;
  }
}
