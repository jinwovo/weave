# ADR 0003 — Undo/redo as inverse ops, not log rewinding

Status: accepted · Date: 2026-06-29

## Context

Users expect Ctrl+Z. The naïve implementation — "remember the previous document and restore it" —
is wrong in a collaborative editor: the document is **shared**. If I move a shape, you recolor it,
and then I undo, restoring my old snapshot would also clobber *your* recolor, which I never touched.
The op-log is append-only and replicated besides, so there is no "previous state" to rewind to — by
the time I undo, the log has grown and other replicas have diverged from any snapshot I held.

Undo has to mean "undo **my** action", expressed in a way that commutes with everyone else's
concurrent edits.

## Decision

Implement undo/redo as **inverse operations re-authored with a fresh timestamp**, on a **per-user**
stack (client-side, in `WeaveClient`).

- For every local edit we push an `UndoEntry { undo, redo }` of **OpTemplates** — an op shape *minus*
  the timestamp:
  - **create** → undo = `DELETE`, redo = the original `CREATE`.
  - **field set** (move / resize / recolor / text) → undo = `SET field = <value before the edit>`,
    redo = `SET field = <new value>`. The before-value is captured *before* the op is applied; for a
    drag/resize it is snapshotted once at the **start of the gesture**, so undo targets the
    pre-drag value rather than some throttled mid-drag frame.
  - **delete** → undo = a `CREATE` reconstructed from the shape's full state (captured before the
    tombstone lands), redo = `DELETE`.
- `undo()` pops the stack, **stamps the inverse with a fresh HLC**, applies it locally and broadcasts
  it as an ordinary op; `redo()` does the same with the forward template. Because the board is a
  last-writer-wins join-semilattice ([ADR-0001](0001-lww-map-crdt-over-hlc.md)), a fresh stamp always
  wins — so undo is just *"author the reverse edit, now"*, which is automatically correct under
  concurrency. No special "undo op", no server involvement.
- A fresh local edit clears the redo stack (standard editor semantics). The stack is capped at 200.

Undelete works for free: `DELETE` only flips the `deleted` register, leaving every other field
register intact, so re-issuing a `CREATE` with a winning timestamp resurrects the shape exactly.

## Alternatives considered

- **Snapshot/restore the whole doc.** Simple single-user, but clobbers concurrent edits to untouched
  fields and needs a full-doc copy per step. Rejected — it isn't collaborative undo.
- **Global (shared) undo** — undo the last edit by *anyone*. Surprising (you undo a stranger's work)
  and rarely what users want. Per-user local undo is the established choice (Figma, Google Docs).
- **A reversible op-log with anti-ops the server understands.** More machinery, couples the server to
  undo semantics, and still reduces to "apply an inverse op" — which is exactly what we do, minus the
  coupling. The server stays a dumb relay.

## Consequences

- ✅ Undo/redo compose with live collaboration: undoing my move after you recolor the same shape
  keeps your color and reverts only the position — each field is its own LWW register.
- ✅ Zero protocol/server change — inverse ops are ordinary ops, so they persist, fan out, and appear
  in time-travel and the hourly archive like any other edit.
- ✅ Proven by `scripts/capture-undo.mjs`: three shapes drawn, then driven through the toolbar's
  ↶/↷, asserting from the rendered canvas alone that undo erases the board to its exact blank
  baseline and redo restores it to the exact prior pixel count, with correct button enablement at
  both ends of each stack.
- ⚠️ Undo is "re-author the reverse", so an undo is itself a new edit in history (it does not erase
  the original op from the log) — the honest model for a replicated log, and the reason redo is also
  just a re-author.
- ⚠️ Scope is **shape/canvas** ops. Character-level text bodies are their own sequence CRDT
  ([ADR-0002](0002-rga-sequence-crdt.md)); the `<textarea>` editor keeps the browser's native
  per-field undo while it is focused, and collaborative-text undo (inverse RGA ops) is future work.
