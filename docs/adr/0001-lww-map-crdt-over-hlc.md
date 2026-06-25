# ADR 0001 — A per-field LWW-Map CRDT over Hybrid Logical Clocks

Status: accepted (P0) · Date: 2026-06-25

## Context

weave is a real-time multiplayer canvas: many clients edit the same board at once, edits
must survive offline periods, and the server must **not** be a single source of truth that
serialises every change behind a lock (that is the realtime-messaging muscle, already shown).
We need shared state that **converges** without coordination — a CRDT — and the convergence
must be *provable*, not merely *demoable*.

## Decision

Model a board as a **map `shapeId → ShapeState`** where:

- Each shape's user-visible properties (`position`, `size`, `color`, `text`, `z`, `deleted`,
  and the immutable `type`) are independent **Last-Writer-Wins registers**.
- A write is ordered by a **Hybrid Logical Clock** timestamp plus the actor id
  (`⟨hlc, actorId⟩`), which is **globally unique and totally ordered**.
- `merge` is the **field-wise, then key-wise least upper bound**. Because each register's
  merge is commutative, associative and idempotent, so is the whole document — it is a
  join-semilattice, which is the textbook sufficient condition for a state-based CRDT (CvRDT)
  to converge.
- An operation (`Create` / `Set` / `Delete`) is applied by merging the tiny `ShapeState`
  delta it implies. Op application therefore inherits convergence for free and is immune to
  reordering and duplication — no operational-transform reconciliation.

Deletion is an LWW boolean tombstone (a later `Create` can resurrect a shape); the server is
a dumb relay + persistence layer.

## Alternatives considered

- **Operational Transformation (OT).** Powerful but notoriously hard to get right; requires a
  central transformation authority and a thicket of transform functions. Rejected: the whole
  point is *coordination-free* convergence.
- **Sequence CRDT (RGA / YATA) for everything.** Necessary for collaborative *rich text*, but
  overkill for a shape map where objects are keyed by id and ordered by a `z` field. We keep a
  fractional-index string in the `z` register now and can graft RGA into a text shape later.
- **OR-Set (add-wins) for shape existence.** Cleaner "concurrent add vs remove" semantics, but
  for a whiteboard LWW remove is predictable and simpler, and matches user expectation (last
  action wins). Revisit if undo/redo demands it.
- **Use a library (Yjs / Automerge).** Would erase the entire reason this project exists —
  the muscle being demonstrated is *implementing* a convergent replicated type, with a
  property-based proof. The core ships with **zero production dependencies**.

## Consequences

- ✅ Convergence is enforced by `jqwik` property tests: order-independence + duplication
  idempotence, partition-then-gossip convergence, and the semilattice laws on `merge`.
- ✅ The core is a pure-Java module with no framework on its classpath, so it can be reasoned
  about and tested in isolation; the Spring sync server (P1) wraps it without changing the math.
- ✅ Per-field LWW gives a great demo: concurrent edits to *different* fields of one shape both
  survive.
- ⚠️ Same-field concurrent edits resolve last-writer-wins, so a "losing" edit is dropped (not
  merged). Acceptable for geometric/style fields; collaborative text inside a shape will need a
  sequence CRDT.
- ⚠️ Tombstones accumulate; a compaction/GC pass is deferred (out of P0 scope).
