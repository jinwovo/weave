package com.portfolio.weave.crdt;

import java.util.Objects;

/**
 * A globally-unique, totally-ordered Last-Writer-Wins timestamp: an {@link Hlc} plus
 * the originating {@code actorId}.
 *
 * <p>The actor id is the final tie-breaker, so two writes from different replicas in the
 * same HLC instant still order deterministically. And because a single actor's HLC never
 * repeats a reading, <em>no two distinct writes ever compare equal</em>. That global
 * uniqueness is precisely what turns the per-field LWW merge into a true join-semilattice
 * (and therefore makes the whole document convergent).
 */
public record Timestamp(Hlc hlc, String actorId) implements Comparable<Timestamp> {

	public Timestamp {
		Objects.requireNonNull(hlc, "hlc");
		Objects.requireNonNull(actorId, "actorId");
	}

	@Override
	public int compareTo(Timestamp o) {
		int byHlc = hlc.compareTo(o.hlc);
		return byHlc != 0 ? byHlc : actorId.compareTo(o.actorId);
	}

	@Override
	public String toString() {
		return hlc + "@" + actorId;
	}
}
