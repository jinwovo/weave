# ADR 0004 — Snapshot-accelerated replay (bounded cold-start recovery)

Status: accepted · Date: 2026-07-02

## Context

The board is event-sourced: the op-log is the durable truth and a room's in-memory document is
its fold. That gave us time-travel and hourly archives for free — but it also meant a cold
start (server restart, first join to an idle room) replayed the **entire** op-log of that
room. Replay work grew with a room's lifetime, not with its current size: the k6 load room
held ~3,500 ops and every cold load re-folded all of them; an image-heavy room re-parses
megabytes of data-URL payloads. That is the classic event-sourcing scaling question — "what
bounds your recovery time?" — and the honest answer was "nothing".

## Decision

Materialise, per room, a **snapshot = the CRDT document itself** (`canvas_snapshot` jsonb),
and make cold start fold *snapshot + tail* instead of the whole log.

- **The snapshot is lossless at the CRDT level.** Every register keeps its value **and** its
  HLC timestamp, and tombstoned shapes stay in. A flattened, render-style snapshot would break
  the next LWW arbitration; a timestamped one keeps merging exactly like the ops it replaced.
- **The watermark is insertion order, not HLC order.** `canvas_op` gains `seq bigserial`;
  a snapshot records `upto_seq`, and replay folds `seq > upto_seq`. Correctness does not
  require the folded subset to be a *causal* prefix: document merge is a join — commutative,
  associative, idempotent — so folding **any** subset, then the rest, in any order, with any
  overlap, rebuilds the exact document. (Property-tested in
  `CanvasDocPropertyTest.aSnapshotOfAnyPrefixPlusTheTailRebuildsTheExactDocument`; this is
  also why a late op from an offline client's outbox — stamped hours ago but inserted now —
  can never be lost to a snapshot: its `seq` is new, so it is always in some tail.)
- **The watermark advances only past a grace horizon** (`weave.snapshot.grace`, default 10 s).
  `seq` values are handed out before their transactions commit, so "max(seq) now" could skip
  a row that commits a moment later; the builder therefore never advances the watermark over
  rows younger than the horizon. Ingest is a single-statement transaction (commit trails the
  seq by milliseconds against a 10-second window), and any row the window makes us re-read is
  absorbed by idempotency. Bounded-staleness assumptions like this are standard in production
  CRDT systems; ours is stated, sized, and cheap.
- **A sweeper, not the ingest path, builds snapshots.** Every `sweep-delay` (30 s) each
  instance refreshes rooms whose un-snapshotted tail passed `threshold` (500 ops). Racing
  instances upsert internally-consistent snapshots — last writer wins, both correct. The tail
  only grows while some instance is alive to ingest, so the sweep cadence — not downtime —
  bounds the cold-start tail.
- **Tombstones are not GC'd.** Dropping a tombstone from the snapshot could resurrect a shape
  if an older-stamped op arrives later (offline outbox flush). Safe GC needs a delivery
  horizon ("no op stamped before T can still arrive"), which we don't have — deferred, and
  documented here rather than hand-waved.

`/history` still reads the full log — time-travel *is* the product feature; snapshots bound
the server's recovery work, they don't rewrite history.

## Alternatives considered

- **Compact (delete) the op-log up to the snapshot.** Kills time-travel and the hourly
  archive — the op-log is a feature, not just a recovery mechanism. Rejected.
- **HLC-ordered watermark.** "Fold everything ≤ some HLC" breaks precisely for the offline
  client whose ops are stamped in the past but inserted late — they'd fall *under* the
  watermark without ever being folded. Insertion order dodges the whole class of bugs.
- **Snapshot the in-memory doc on ingest.** The live doc also contains ops from *other*
  instances (via Redis) whose `seq` this instance cannot see, so no watermark can be attached
  to it safely. The DB-fold builder reads `(ops, watermark)` from one consistent place.
- **Exact commit-frontier tracking (pg_snapshot / outbox-style).** Airtight but heavyweight;
  the grace window achieves the same guarantee under a stated, generous assumption.

## Consequences

- Cold-start replay is **O(tail)**, tail ≤ threshold + one sweep window of ops. Live numbers
  from the dev stack: rooms of 3,469 / 1,205 / 100 ops all cold-loaded with **0 tail ops**
  folded after the first sweep (previously: the full count each).
- New metrics: `weave_snapshot_writes_total`, `weave_snapshot_refresh_seconds`,
  `weave_replay_seconds`, `weave_replay_tail_ops` (Grafana row "snapshots").
- Text ops (RGA) are *not* snapshotted yet — the server relays them opaquely and clients fold
  them; a text snapshot would serialise per-shape RGAs (`crdt-core` has the type). Future.
