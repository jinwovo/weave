# ADR 0002 — A Replicated Growable Array (RGA) for collaborative text

Status: accepted · Date: 2026-06-25

## Context

[ADR-0001](0001-lww-map-crdt-over-hlc.md) made every shape field a **Last-Writer-Wins** register.
That is correct for geometry and style (you don't merge two `x` positions — the later one wins), but
it is wrong for **text**: if two people type into the same note at once, LWW throws one person's whole
edit away. Real collaborative text has to converge *character by character*.

LWW is the easy kind of CRDT. The hard, interesting kind is a **sequence CRDT**, and that is exactly
the muscle worth proving here.

## Decision

Implement an **RGA (Replicated Growable Array)** — `crdt-core/RgaText` — as the content type for text:

- Every character is an element with a globally-unique **`Timestamp` id** (HLC + actor).
- An insert names the element it goes **after** (`originLeft`, or the head). Concurrent inserts at the
  same spot are ordered **by id, greater first**, so the scan that places a new element is a
  deterministic function of the op-set — every replica computes the same string.
- Deletes are **tombstones** keyed by the target id, so positions referenced by concurrent ops stay
  valid.
- Ops are **buffered until their dependency is present** (`originLeft` for an insert, the target for a
  delete), so the type converges under *any* delivery order and with duplicates — not just causal,
  in-order delivery.

Text ops (`TextOp.Insert` / `TextOp.Delete`) travel and persist exactly like canvas ops; the server
stays a dumb relay + op-log, oblivious to RGA semantics.

## Alternatives considered

- **Keep LWW text.** Simple, but silently destroys concurrent edits — not collaborative editing at all.
- **Logoot / LSEQ (dense position identifiers).** Avoids tombstones but identifiers can grow without
  bound and the allocation strategy is finicky. RGA is the more teachable, widely-implemented choice.
- **YATA (Yjs) / Fugue.** Refine the ordering rule to avoid the interleaving anomaly (below). More
  complex (YATA tracks both left and right origins). Deferred — RGA first, with the limitation
  documented honestly.

## Consequences

- ✅ Convergence is proven, not asserted: `RgaTextPropertyTest` delivers concurrently-authored ops in
  every order (with duplicates) and asserts one string; `ConvergenceSimulationTest` is a
  **deterministic fault-injection simulation** (Jepsen-lite / DST) — 3–4 replicas under an adversarial
  network that delays, reorders, drops-and-redelivers and duplicates every op — that asserts
  convergence across 400 seeds, each run a reproducible function of its seed.
- ✅ The server is unchanged in spirit: text ops are just more ops in the append-only log, so
  time-travel and the hourly archive cover text too.
- ⚠️ **Interleaving anomaly:** plain RGA can interleave two users' concurrent *runs* of characters in
  adversarial cases (e.g. both typing at the same position). The result still converges; it is the
  *ordering quality* that suffers. YATA and Fugue fix this and are the natural next step.
- ⚠️ Tombstones accumulate (shared with ADR-0001); causal-stability GC is future work.
