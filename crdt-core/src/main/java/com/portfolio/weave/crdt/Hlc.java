package com.portfolio.weave.crdt;

/**
 * A Hybrid Logical Clock reading: a physical wall-clock component {@code l}
 * (milliseconds) paired with a monotonic logical counter {@code c} that breaks ties
 * between events that fall in the same millisecond.
 *
 * <p>HLC keeps timestamps close to physical time (so a human reading the op-log sees
 * sensible wall-clock order) while still respecting causality: a received event can
 * only push a clock <em>forward</em>. That is exactly the property a Last-Writer-Wins
 * register needs to pick a winner deterministically across replicas.
 *
 * <p>Ordering is lexicographic — first by {@code l}, then by {@code c}.
 */
public record Hlc(long l, int c) implements Comparable<Hlc> {

	public static final Hlc ZERO = new Hlc(0L, 0);

	@Override
	public int compareTo(Hlc o) {
		int byL = Long.compare(l, o.l);
		return byL != 0 ? byL : Integer.compare(c, o.c);
	}

	@Override
	public String toString() {
		return l + ":" + c;
	}
}
