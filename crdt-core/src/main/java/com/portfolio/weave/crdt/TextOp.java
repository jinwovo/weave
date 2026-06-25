package com.portfolio.weave.crdt;

/**
 * An operation on an {@link RgaText} sequence CRDT.
 *
 * <p>Unlike the canvas's per-field LWW (where the "last" whole value wins), text needs every
 * <em>character</em> to survive concurrent edits. So each inserted character is its own element with
 * a globally-unique {@link Timestamp} id, positioned immediately after an existing element
 * ({@code originLeft}, or the head when {@code null}). Deletion is a tombstone keyed by the target
 * element's id, which keeps positions stable for concurrent inserts.
 */
public sealed interface TextOp permits TextOp.Insert, TextOp.Delete {

	/** Insert {@code value} as a new element with id {@code id}, immediately to the right of {@code originLeft}. */
	record Insert(Timestamp id, Timestamp originLeft, char value) implements TextOp {
	}

	/** Tombstone the element with id {@code target}. */
	record Delete(Timestamp target) implements TextOp {
	}
}
