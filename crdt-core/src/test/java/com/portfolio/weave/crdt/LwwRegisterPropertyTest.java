package com.portfolio.weave.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * The single register is the atom of the whole CRDT: if its merge is a join-semilattice
 * (commutative, associative, idempotent) then everything built on top of it converges.
 */
class LwwRegisterPropertyTest {

	/**
	 * Registers whose value is derived from their timestamp, so the generator can never
	 * produce the input the type forbids by construction — "same timestamp, different value"
	 * (globally-unique HLC stamps guarantee that never happens in production). Distinct
	 * timestamps therefore drive every interesting merge decision.
	 */
	@Provide
	Arbitrary<LwwRegister<String>> registers() {
		Arbitrary<Long> l = Arbitraries.longs().between(0, 30);
		Arbitrary<Integer> c = Arbitraries.integers().between(0, 4);
		Arbitrary<String> actor = Arbitraries.of("a", "b", "c");
		return Combinators.combine(l, c, actor).as((ll, cc, aa) -> {
			Timestamp ts = new Timestamp(new Hlc(ll, cc), aa);
			return LwwRegister.of("v@" + ts, ts);
		});
	}

	@Property
	void idempotent(@ForAll("registers") LwwRegister<String> r) {
		assertThat(r.merge(r)).isEqualTo(r);
	}

	@Property
	void commutative(@ForAll("registers") LwwRegister<String> a, @ForAll("registers") LwwRegister<String> b) {
		assertThat(a.merge(b)).isEqualTo(b.merge(a));
	}

	@Property
	void associative(@ForAll("registers") LwwRegister<String> a,
	                 @ForAll("registers") LwwRegister<String> b,
	                 @ForAll("registers") LwwRegister<String> c) {
		assertThat(a.merge(b).merge(c)).isEqualTo(a.merge(b.merge(c)));
	}

	@Property
	void mergeKeepsTheGreaterTimestamp(@ForAll("registers") LwwRegister<String> a,
	                                   @ForAll("registers") LwwRegister<String> b) {
		LwwRegister<String> winner = a.ts().compareTo(b.ts()) >= 0 ? a : b;
		assertThat(a.merge(b)).isEqualTo(winner);
	}

	@Example
	void concurrentWritesToTheSameFieldResolveByTimestampEitherWay() {
		Timestamp early = new Timestamp(new Hlc(1, 0), "a");
		Timestamp late = new Timestamp(new Hlc(2, 0), "b");
		LwwRegister<String> red = LwwRegister.of("red", early);
		LwwRegister<String> blue = LwwRegister.of("blue", late);
		assertThat(red.merge(blue).value()).isEqualTo("blue");
		assertThat(blue.merge(red).value()).isEqualTo("blue"); // order-independent
	}

	@Example
	void nullIsTheBottomElement() {
		Timestamp ts = new Timestamp(new Hlc(1, 0), "a");
		LwwRegister<String> r = LwwRegister.of("x", ts);
		assertThat(LwwRegister.mergeNullable(null, r)).isEqualTo(r);
		assertThat(LwwRegister.mergeNullable(r, null)).isEqualTo(r);
		assertThat(LwwRegister.<String>mergeNullable(null, null)).isNull();
	}
}
