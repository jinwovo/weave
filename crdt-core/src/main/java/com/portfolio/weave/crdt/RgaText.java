package com.portfolio.weave.crdt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A Replicated Growable Array (RGA) — a <em>sequence</em> CRDT for collaboratively-edited text.
 *
 * <p>This is the genuinely hard kind of CRDT (the canvas uses last-writer-wins, the easy kind). Each
 * character is an element tagged with a globally-unique {@link Timestamp}; an insert names the
 * element it goes after, and concurrent inserts at the same spot are ordered deterministically by id
 * (higher id first) so every replica reaches the same string. Deletes are tombstones — kept so that
 * positions referenced by concurrent ops stay valid.
 *
 * <p>Operations are buffered until their dependency is present ({@code originLeft} for an insert, the
 * target for a delete), so the type converges under <em>any</em> delivery order, with duplicates —
 * which is what the deterministic fault-injection simulation hammers on.
 *
 * <p>Known limitation (documented, not a bug): plain RGA can <em>interleave</em> two users' concurrent
 * runs of text in adversarial cases; YATA (Yjs) and Fugue refine the ordering rule to avoid it. See
 * {@code docs/adr/0002-rga-sequence-crdt.md}.
 */
public final class RgaText {

	private static final class Node {
		final Timestamp id;
		final char value;
		boolean deleted;

		Node(Timestamp id, char value) {
			this.id = id;
			this.value = value;
		}
	}

	private final List<Node> nodes = new ArrayList<>(); // document order, tombstones included
	private final Map<Timestamp, Node> byId = new HashMap<>();
	private final List<TextOp> pending = new ArrayList<>(); // ops waiting for a missing dependency

	/** Apply a (possibly out-of-order, possibly duplicate) op, then drain anything it unblocked. */
	public void apply(TextOp op) {
		if (tryApply(op)) {
			drainPending();
		} else {
			pending.add(op);
		}
	}

	private boolean tryApply(TextOp op) {
		return switch (op) {
			case TextOp.Insert ins -> {
				if (byId.containsKey(ins.id())) {
					yield true; // idempotent — already inserted
				}
				if (ins.originLeft() != null && !byId.containsKey(ins.originLeft())) {
					yield false; // dependency not here yet
				}
				insert(ins);
				yield true;
			}
			case TextOp.Delete del -> {
				Node n = byId.get(del.target());
				if (n == null) {
					yield false; // target not here yet
				}
				n.deleted = true;
				yield true;
			}
		};
	}

	private void insert(TextOp.Insert ins) {
		int i = (ins.originLeft() == null) ? 0 : indexOf(ins.originLeft()) + 1;
		// Skip the run of concurrent inserts that sort before this one (greater id wins the earlier
		// slot). Stopping at the first smaller-id element makes the placement a deterministic function
		// of the op set, so every replica agrees.
		while (i < nodes.size() && nodes.get(i).id.compareTo(ins.id()) > 0) {
			i++;
		}
		Node node = new Node(ins.id(), ins.value());
		nodes.add(i, node);
		byId.put(ins.id(), node);
	}

	private void drainPending() {
		boolean progress = true;
		while (progress) {
			progress = false;
			for (int i = pending.size() - 1; i >= 0; i--) {
				if (tryApply(pending.get(i))) {
					pending.remove(i);
					progress = true;
				}
			}
		}
	}

	private int indexOf(Timestamp id) {
		for (int i = 0; i < nodes.size(); i++) {
			if (nodes.get(i).id.equals(id)) {
				return i;
			}
		}
		return -1; // unreachable: callers guard with byId.containsKey
	}

	/** The visible string (tombstones skipped). */
	public String value() {
		StringBuilder sb = new StringBuilder(nodes.size());
		for (Node n : nodes) {
			if (!n.deleted) {
				sb.append(n.value);
			}
		}
		return sb.toString();
	}

	/** Number of visible characters. */
	public int length() {
		int n = 0;
		for (Node node : nodes) {
			if (!node.deleted) {
				n++;
			}
		}
		return n;
	}

	private Timestamp visibleId(int visIndex) {
		int seen = 0;
		for (Node n : nodes) {
			if (n.deleted) {
				continue;
			}
			if (seen == visIndex) {
				return n.id;
			}
			seen++;
		}
		throw new IndexOutOfBoundsException("visible index " + visIndex + " of " + length());
	}

	// --- local authoring (the caller stamps the id with its own HLC + actor) ---

	/** Insert {@code value} at visible position {@code visIndex} (0 = start), returning the op to broadcast. */
	public TextOp.Insert localInsert(int visIndex, char value, Timestamp id) {
		Timestamp originLeft = (visIndex == 0) ? null : visibleId(visIndex - 1);
		TextOp.Insert op = new TextOp.Insert(id, originLeft, value);
		apply(op);
		return op;
	}

	/** Delete the visible character at {@code visIndex}, returning the op to broadcast. */
	public TextOp.Delete localDelete(int visIndex) {
		TextOp.Delete op = new TextOp.Delete(visibleId(visIndex));
		apply(op);
		return op;
	}
}
