package com.portfolio.weave.crdt;

import java.util.UUID;

/**
 * The convergent state of a single canvas object. Every user-visible property is an
 * independent {@link LwwRegister}, so two people editing <em>different</em> properties of
 * the same shape at the same moment both keep their change (you drag it while I recolour
 * it — both stick). A {@code null} register means "this replica has not yet observed any
 * write to that field" and serves as the semilattice bottom.
 *
 * <p>{@link #merge(ShapeState)} is the field-wise least upper bound. Since each component
 * register is a join-semilattice, their product is too — which is what makes a whole
 * {@link CanvasDoc} converge.
 */
public record ShapeState(
		UUID id,
		LwwRegister<ShapeType> type,
		LwwRegister<Vec2> position,
		LwwRegister<Vec2> size,
		LwwRegister<String> color,
		LwwRegister<String> text,
		LwwRegister<String> z,
		LwwRegister<Boolean> deleted
) {

	/** A shape known only by id, with every field still at bottom. */
	public static ShapeState empty(UUID id) {
		return new ShapeState(id, null, null, null, null, null, null, null);
	}

	/** Field-wise least upper bound with another state for the same shape. */
	public ShapeState merge(ShapeState o) {
		return new ShapeState(
				id,
				LwwRegister.mergeNullable(type, o.type),
				LwwRegister.mergeNullable(position, o.position),
				LwwRegister.mergeNullable(size, o.size),
				LwwRegister.mergeNullable(color, o.color),
				LwwRegister.mergeNullable(text, o.text),
				LwwRegister.mergeNullable(z, o.z),
				LwwRegister.mergeNullable(deleted, o.deleted)
		);
	}

	/** True once a CREATE has been observed and no later DELETE supersedes it (LWW remove). */
	public boolean live() {
		return type != null && (deleted == null || !deleted.value());
	}
}
