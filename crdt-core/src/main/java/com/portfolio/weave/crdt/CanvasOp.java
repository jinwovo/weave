package com.portfolio.weave.crdt;

import java.util.UUID;

/**
 * An intent-carrying mutation to the canvas, as it travels over the wire and is appended
 * to the op-log. Applying an op is <em>defined</em> as merging the small {@link ShapeState}
 * delta it implies (see {@link #delta()}) into the document, so op application inherits the
 * exact convergence guarantees of state merge: it is insensitive to delivery order and to
 * duplication, with no special "operational transform" reconciliation needed.
 */
public sealed interface CanvasOp permits CanvasOp.Create, CanvasOp.Set, CanvasOp.Delete {

	UUID shapeId();

	Timestamp ts();

	/** Materialise the {@link ShapeState} delta this op contributes to a document. */
	ShapeState delta();

	/** Which single field a {@link Set} op writes. */
	enum Field { POSITION, SIZE, COLOR, TEXT, Z }

	/** Create a shape, stamping every field with the creation timestamp (and not-deleted). */
	record Create(
			UUID shapeId, ShapeType type, Vec2 position, Vec2 size,
			String color, String text, String z, Timestamp ts
	) implements CanvasOp {

		@Override
		public ShapeState delta() {
			return new ShapeState(
					shapeId,
					LwwRegister.of(type, ts),
					LwwRegister.of(position, ts),
					LwwRegister.of(size, ts),
					LwwRegister.of(color, ts),
					LwwRegister.of(text, ts),
					LwwRegister.of(z, ts),
					LwwRegister.of(Boolean.FALSE, ts)
			);
		}
	}

	/** Write exactly one field of an existing shape (move / resize / recolour / edit / reorder). */
	record Set(UUID shapeId, Field field, Object value, Timestamp ts) implements CanvasOp {

		public static Set position(UUID id, Vec2 v, Timestamp ts) { return new Set(id, Field.POSITION, v, ts); }
		public static Set size(UUID id, Vec2 v, Timestamp ts)     { return new Set(id, Field.SIZE, v, ts); }
		public static Set color(UUID id, String v, Timestamp ts)  { return new Set(id, Field.COLOR, v, ts); }
		public static Set text(UUID id, String v, Timestamp ts)   { return new Set(id, Field.TEXT, v, ts); }
		public static Set z(UUID id, String v, Timestamp ts)      { return new Set(id, Field.Z, v, ts); }

		@Override
		public ShapeState delta() {
			return switch (field) {
				case POSITION -> new ShapeState(shapeId, null, LwwRegister.of((Vec2) value, ts), null, null, null, null, null);
				case SIZE     -> new ShapeState(shapeId, null, null, LwwRegister.of((Vec2) value, ts), null, null, null, null);
				case COLOR    -> new ShapeState(shapeId, null, null, null, LwwRegister.of((String) value, ts), null, null, null);
				case TEXT     -> new ShapeState(shapeId, null, null, null, null, LwwRegister.of((String) value, ts), null, null);
				case Z        -> new ShapeState(shapeId, null, null, null, null, null, LwwRegister.of((String) value, ts), null);
			};
		}
	}

	/** Tombstone a shape (last-writer-wins remove; a later CREATE can resurrect it). */
	record Delete(UUID shapeId, Timestamp ts) implements CanvasOp {

		@Override
		public ShapeState delta() {
			return new ShapeState(shapeId, null, null, null, null, null, null, LwwRegister.of(Boolean.TRUE, ts));
		}
	}
}
