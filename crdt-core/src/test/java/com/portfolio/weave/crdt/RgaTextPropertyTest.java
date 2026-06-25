package com.portfolio.weave.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

/**
 * A sequence CRDT only earns the name if concurrent edits to the same text converge to one string,
 * character by character — not last-writer-wins. These properties simulate replicas editing diverged
 * copies, then deliver the resulting ops in every order (with duplicates) and assert one result.
 */
class RgaTextPropertyTest {

	private static final char[] ALPHABET = "abcde".toCharArray();

	/** Simulate `replicas` editing independently (never syncing) and collect every op they emit. */
	private static List<TextOp> concurrentEdits(int replicas, int steps, long seed) {
		Random rnd = new Random(seed);
		long[] phys = { 1000L };
		List<RgaText> texts = new ArrayList<>();
		List<HlcClock> clocks = new ArrayList<>();
		List<String> actors = new ArrayList<>();
		for (int i = 0; i < replicas; i++) {
			texts.add(new RgaText());
			clocks.add(new HlcClock(() -> phys[0]++));
			actors.add("r" + i);
		}
		List<TextOp> ops = new ArrayList<>();
		for (int s = 0; s < steps; s++) {
			int r = rnd.nextInt(replicas);
			RgaText t = texts.get(r);
			int len = t.length();
			if (len > 0 && rnd.nextInt(3) == 0) {
				ops.add(t.localDelete(rnd.nextInt(len)));
			} else {
				Timestamp id = new Timestamp(clocks.get(r).tick(), actors.get(r));
				ops.add(t.localInsert(rnd.nextInt(len + 1), ALPHABET[rnd.nextInt(ALPHABET.length)], id));
			}
		}
		return ops;
	}

	private static String deliver(List<TextOp> ops) {
		RgaText t = new RgaText();
		for (TextOp op : ops) {
			t.apply(op);
		}
		return t.value();
	}

	@Property(tries = 300)
	void convergesUnderAnyDeliveryOrderAndDuplication(
			@ForAll @IntRange(min = 2, max = 4) int replicas,
			@ForAll @IntRange(min = 1, max = 45) int steps,
			@ForAll @LongRange(min = 1, max = 1_000_000) long seed) {
		List<TextOp> ops = concurrentEdits(replicas, steps, seed);
		String canonical = deliver(ops);
		Random rnd = new Random(seed * 31 + 7);
		for (int trial = 0; trial < 6; trial++) {
			List<TextOp> delivery = new ArrayList<>(ops);
			for (TextOp op : ops) {
				if (rnd.nextInt(4) == 0) {
					delivery.add(op); // duplicates must be absorbed
				}
			}
			Collections.shuffle(delivery, rnd);
			assertThat(deliver(delivery)).isEqualTo(canonical);
		}
	}

	@Property(tries = 200)
	void mergedTextContainsOnlyRealCharacters(
			@ForAll @IntRange(min = 2, max = 4) int replicas,
			@ForAll @IntRange(min = 1, max = 40) int steps,
			@ForAll @LongRange(min = 1, max = 1_000_000) long seed) {
		String v = deliver(concurrentEdits(replicas, steps, seed));
		for (int i = 0; i < v.length(); i++) {
			assertThat(ALPHABET).contains(v.charAt(i));
		}
	}

	@Example
	void concurrentInsertsAtTheSameSpotKeepBothCharactersDeterministically() {
		RgaText a = new RgaText();
		TextOp opA = a.localInsert(0, 'A', new Timestamp(new Hlc(1, 0), "a"));
		RgaText b = new RgaText();
		TextOp opB = b.localInsert(0, 'B', new Timestamp(new Hlc(2, 0), "b")); // greater id

		a.apply(opB);
		b.apply(opA);

		assertThat(a.value()).isEqualTo(b.value());
		assertThat(a.value()).isEqualTo("BA"); // greater id sorts first at the same origin
	}

	@Example
	void deleteBeforeItsInsertArrivesIsBufferedThenApplied() {
		RgaText a = new RgaText();
		TextOp ins = a.localInsert(0, 'x', new Timestamp(new Hlc(5, 0), "a"));
		TextOp del = a.localDelete(0);

		RgaText b = new RgaText();
		b.apply(del); // arrives first — target not present yet, must be buffered
		assertThat(b.value()).isEmpty();
		b.apply(ins);
		assertThat(b.value()).isEmpty(); // once the insert lands, the buffered delete tombstones it
	}
}
