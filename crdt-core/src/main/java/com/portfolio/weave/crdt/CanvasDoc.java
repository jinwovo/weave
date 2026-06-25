package com.portfolio.weave.crdt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A conflict-free replicated whiteboard document: a map from shape id to its
 * {@link ShapeState}, with {@link #merge(CanvasDoc) merge} defined as the key-wise least
 * upper bound. Two replicas that have observed the same set of operations — in any order,
 * with any duplicates — are guaranteed to hold equal documents.
 *
 * <p>The class is immutable: every mutation returns a new instance, which keeps the type
 * trivially safe to share across the relay's threads and makes the property tests read as
 * plain algebra.
 */
public final class CanvasDoc {

	private final Map<UUID, ShapeState> shapes;

	public CanvasDoc() {
		this(Map.of());
	}

	private CanvasDoc(Map<UUID, ShapeState> shapes) {
		this.shapes = shapes;
	}

	/** An unmodifiable view of every shape this replica knows about (including tombstoned). */
	public Map<UUID, ShapeState> shapes() {
		return Collections.unmodifiableMap(shapes);
	}

	/** The shapes a client would actually draw: created and not deleted. */
	public List<ShapeState> liveShapes() {
		return shapes.values().stream().filter(ShapeState::live).collect(Collectors.toList());
	}

	/** Apply a single operation, returning the resulting document. */
	public CanvasDoc apply(CanvasOp op) {
		return mergeShape(op.shapeId(), op.delta());
	}

	/** Apply many operations in iteration order. */
	public CanvasDoc applyAll(Iterable<? extends CanvasOp> ops) {
		CanvasDoc doc = this;
		for (CanvasOp op : ops) {
			doc = doc.apply(op);
		}
		return doc;
	}

	private CanvasDoc mergeShape(UUID id, ShapeState delta) {
		Map<UUID, ShapeState> next = new LinkedHashMap<>(shapes);
		ShapeState cur = next.get(id);
		next.put(id, cur == null ? delta : cur.merge(delta));
		return new CanvasDoc(next);
	}

	/** Least upper bound of two documents — the CRDT join. */
	public CanvasDoc merge(CanvasDoc other) {
		Map<UUID, ShapeState> next = new LinkedHashMap<>(shapes);
		for (Map.Entry<UUID, ShapeState> e : other.shapes.entrySet()) {
			next.merge(e.getKey(), e.getValue(), ShapeState::merge);
		}
		return new CanvasDoc(next);
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof CanvasDoc d && shapes.equals(d.shapes);
	}

	@Override
	public int hashCode() {
		return shapes.hashCode();
	}

	@Override
	public String toString() {
		return "CanvasDoc{shapes=" + shapes.size() + ", live=" + liveShapes().size() + "}";
	}
}
