package com.portfolio.weave.crdt;

/**
 * A Last-Writer-Wins register: a value tagged with the {@link Timestamp} of the write
 * that produced it. {@link #merge(LwwRegister) Merging} keeps the value carried by the
 * greater timestamp, so the operation is commutative, associative and idempotent — a
 * join-semilattice with the higher-timestamp register as the least upper bound.
 *
 * <p>Because {@link Timestamp}s are globally unique, the {@code >= 0} tie branch is only
 * ever taken for two physically identical writes (same value), so commutativity holds
 * unconditionally rather than merely "up to the tie-break".
 *
 * @param <T> the registered value type, treated as opaque and immutable
 */
public record LwwRegister<T>(T value, Timestamp ts) {

	public static <T> LwwRegister<T> of(T value, Timestamp ts) {
		return new LwwRegister<>(value, ts);
	}

	/** Least upper bound of this register and another non-null register. */
	public LwwRegister<T> merge(LwwRegister<T> other) {
		if (other == null) {
			return this;
		}
		return this.ts.compareTo(other.ts) >= 0 ? this : other;
	}

	/** Null-safe least upper bound, where {@code null} is the semilattice bottom (absent field). */
	public static <T> LwwRegister<T> mergeNullable(LwwRegister<T> a, LwwRegister<T> b) {
		if (a == null) {
			return b;
		}
		return a.merge(b);
	}
}
