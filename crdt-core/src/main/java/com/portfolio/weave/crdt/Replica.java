package com.portfolio.weave.crdt;

/**
 * A minimal stand-in for one connected client/server replica in tests and demos: it owns a
 * {@link CanvasDoc} and an {@link HlcClock}, lets you author ops locally (stamped with this
 * actor's next timestamp) and fold in remote state. The real sync server (P1) wraps this
 * same core with transport, an op-log and persistence — the convergence math does not change.
 */
public final class Replica {

	private final String actorId;
	private final HlcClock clock;
	private CanvasDoc doc = new CanvasDoc();

	public Replica(String actorId) {
		this(actorId, new HlcClock());
	}

	public Replica(String actorId, HlcClock clock) {
		this.actorId = actorId;
		this.clock = clock;
	}

	public String actorId() {
		return actorId;
	}

	public CanvasDoc doc() {
		return doc;
	}

	/** The next locally-authored timestamp for this actor. */
	public Timestamp stamp() {
		return new Timestamp(clock.tick(), actorId);
	}

	/** Apply a locally-authored op to this replica. */
	public CanvasDoc apply(CanvasOp op) {
		doc = doc.apply(op);
		return doc;
	}

	/** Fold another replica's full state into this one. */
	public CanvasDoc mergeFrom(CanvasDoc remote) {
		doc = doc.merge(remote);
		return doc;
	}
}
