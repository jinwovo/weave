package com.portfolio.weave.crdt;

import java.util.function.LongSupplier;

/**
 * A per-actor Hybrid Logical Clock (Kulkarni et al., 2014).
 *
 * <p>Not thread-safe by design: each replica owns exactly one clock and drives it from
 * a single thread (the sync server serialises a room's ops; a browser client serialises
 * its own). The physical-time source is injectable so the algorithm can be driven
 * deterministically from tests instead of depending on the wall clock.
 */
public final class HlcClock {

	private final LongSupplier physicalMillis;
	private Hlc now = Hlc.ZERO;

	public HlcClock() {
		this(System::currentTimeMillis);
	}

	public HlcClock(LongSupplier physicalMillis) {
		this.physicalMillis = physicalMillis;
	}

	/** Stamp a locally generated event, advancing the clock past its previous reading. */
	public Hlc tick() {
		long pt = physicalMillis.getAsLong();
		long l = Math.max(now.l(), pt);
		int c = (l == now.l()) ? now.c() + 1 : 0;
		now = new Hlc(l, c);
		return now;
	}

	/**
	 * Fold a timestamp observed from another replica into this clock and stamp the
	 * receive event. The new reading dominates both the previous local reading and the
	 * remote one, preserving the happens-before relation.
	 */
	public Hlc receive(Hlc remote) {
		long pt = physicalMillis.getAsLong();
		long l = Math.max(Math.max(now.l(), remote.l()), pt);
		int c;
		if (l == now.l() && l == remote.l()) {
			c = Math.max(now.c(), remote.c()) + 1;
		} else if (l == now.l()) {
			c = now.c() + 1;
		} else if (l == remote.l()) {
			c = remote.c() + 1;
		} else {
			c = 0;
		}
		now = new Hlc(l, c);
		return now;
	}

	/** The current reading without advancing the clock. */
	public Hlc peek() {
		return now;
	}
}
