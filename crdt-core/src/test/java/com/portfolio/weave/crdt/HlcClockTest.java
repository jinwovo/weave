package com.portfolio.weave.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The HLC is what gives every write a deterministic, causality-respecting timestamp. Drive
 * it from a controllable physical-time source so the algorithm is pinned down exactly,
 * with no dependency on the wall clock.
 */
class HlcClockTest {

	@Test
	void tickIsStrictlyMonotonicEvenWhenPhysicalTimeStandsStill() {
		AtomicLong physical = new AtomicLong(1000);
		HlcClock clock = new HlcClock(physical::get);
		Hlc t1 = clock.tick();
		Hlc t2 = clock.tick();
		Hlc t3 = clock.tick();
		assertThat(t1).isEqualTo(new Hlc(1000, 0));
		assertThat(t2).isEqualTo(new Hlc(1000, 1)); // same millisecond -> logical counter advances
		assertThat(t3).isEqualTo(new Hlc(1000, 2));
		assertThat(t1.compareTo(t2)).isNegative();
		assertThat(t2.compareTo(t3)).isNegative();
	}

	@Test
	void physicalProgressResetsTheLogicalCounter() {
		AtomicLong physical = new AtomicLong(1000);
		HlcClock clock = new HlcClock(physical::get);
		clock.tick();          // 1000:0
		clock.tick();          // 1000:1
		physical.set(1005);
		assertThat(clock.tick()).isEqualTo(new Hlc(1005, 0));
	}

	@Test
	void receiveAdvancesPastAFutureRemoteStamp() {
		AtomicLong physical = new AtomicLong(1000);
		HlcClock clock = new HlcClock(physical::get);
		clock.tick();          // 1000:0 — local physical time lags the remote
		Hlc remote = new Hlc(2000, 5);
		Hlc after = clock.receive(remote);
		assertThat(after).isEqualTo(new Hlc(2000, 6)); // adopt remote l, counter = max+1
		assertThat(after.compareTo(remote)).isPositive();
	}

	@Test
	void receiveWithEqualPhysicalTakesMaxCounterPlusOne() {
		AtomicLong physical = new AtomicLong(2000);
		HlcClock clock = new HlcClock(physical::get);
		clock.tick();          // 2000:0
		clock.tick();          // 2000:1
		Hlc remote = new Hlc(2000, 7);
		assertThat(clock.receive(remote)).isEqualTo(new Hlc(2000, 8));
	}
}
