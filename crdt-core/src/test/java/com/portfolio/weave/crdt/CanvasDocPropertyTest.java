package com.portfolio.weave.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * The headline guarantee: replicas that observe the same edits — in any order, with any
 * duplication, after arbitrary network partitions — end up byte-for-byte identical. These
 * properties are the difference between "a real CRDT" and "a pretty multiplayer demo that
 * silently diverges under concurrency".
 */
class CanvasDocPropertyTest {

	// A small fixed pool of shape ids so generated ops collide on the same shapes and
	// fields — that collision is what actually exercises conflict resolution.
	private static final List<UUID> IDS = List.of(
			UUID.fromString("00000000-0000-0000-0000-0000000000a1"),
			UUID.fromString("00000000-0000-0000-0000-0000000000a2"),
			UUID.fromString("00000000-0000-0000-0000-0000000000a3"));

	record OpSpec(UUID id, int kind, String actor, double x, double y, String s) {
		CanvasOp toOp(Timestamp ts) {
			return switch (kind) {
				case 0 -> new CanvasOp.Create(id, ShapeType.RECT, new Vec2(x, y), new Vec2(x + 10, y + 10),
						"#" + s, "t" + s, "z" + s, ts);
				case 1 -> CanvasOp.Set.position(id, new Vec2(x, y), ts);
				case 2 -> CanvasOp.Set.size(id, new Vec2(x, y), ts);
				case 3 -> CanvasOp.Set.color(id, "#" + s, ts);
				case 4 -> CanvasOp.Set.text(id, "t" + s, ts);
				case 5 -> CanvasOp.Set.z(id, "z" + s, ts);
				default -> new CanvasOp.Delete(id, ts);
			};
		}
	}

	/**
	 * A realistic op-log over a few shapes. Each op gets a globally-unique, strictly
	 * increasing timestamp (HLC l = list index) — what one logical stream of edits looks
	 * like — and the tests then re-deliver that log in every order imaginable.
	 */
	@Provide
	Arbitrary<List<CanvasOp>> opLogs() {
		Arbitrary<UUID> id = Arbitraries.of(IDS);
		Arbitrary<Integer> kind = Arbitraries.integers().between(0, 6);
		Arbitrary<String> actor = Arbitraries.of("a", "b", "c");
		Arbitrary<Double> coord = Arbitraries.doubles().between(0, 500);
		Arbitrary<String> str = Arbitraries.strings().withCharRange('a', 'f').ofMinLength(1).ofMaxLength(4);
		Arbitrary<OpSpec> spec = Combinators.combine(id, kind, actor, coord, coord, str)
				.as((idv, kindv, actorv, xv, yv, sv) -> new OpSpec(idv, kindv, actorv, xv, yv, sv));
		return spec.list().ofMinSize(1).ofMaxSize(40).map(specs -> {
			List<CanvasOp> ops = new ArrayList<>(specs.size());
			for (int i = 0; i < specs.size(); i++) {
				OpSpec sp = specs.get(i);
				Timestamp ts = new Timestamp(new Hlc(i, 0), sp.actor());
				ops.add(sp.toOp(ts));
			}
			return ops;
		});
	}

	@Property(tries = 200)
	void convergesRegardlessOfDeliveryOrderAndDuplication(@ForAll("opLogs") List<CanvasOp> ops) {
		CanvasDoc canonical = new CanvasDoc().applyAll(ops);
		Random rnd = new Random(ops.size() * 1009L + 7);
		for (int trial = 0; trial < 8; trial++) {
			List<CanvasOp> delivery = new ArrayList<>(ops);
			// re-deliver a random subset; duplicates must be absorbed idempotently
			for (CanvasOp op : ops) {
				if (rnd.nextBoolean()) {
					delivery.add(op);
				}
			}
			Collections.shuffle(delivery, rnd);
			assertThat(new CanvasDoc().applyAll(delivery)).isEqualTo(canonical);
		}
	}

	@Property(tries = 100)
	void replicasPartitionedThenGossipConverge(@ForAll("opLogs") List<CanvasOp> ops) {
		CanvasDoc canonical = new CanvasDoc().applyAll(ops);
		int replicas = 3;
		List<CanvasDoc> reps = new ArrayList<>();
		for (int i = 0; i < replicas; i++) {
			reps.add(new CanvasDoc());
		}
		// each replica originally sees only its slice of the edits (a partition)
		for (int i = 0; i < ops.size(); i++) {
			int who = i % replicas;
			reps.set(who, reps.get(who).apply(ops.get(i)));
		}
		// random pairwise gossip ...
		Random rnd = new Random(ops.size() * 131L + 3);
		for (int round = 0; round < replicas * 4; round++) {
			int a = rnd.nextInt(replicas);
			int b = rnd.nextInt(replicas);
			reps.set(a, reps.get(a).merge(reps.get(b)));
		}
		// ... then a guaranteed full exchange: everyone converges to the canonical state
		for (int i = 0; i < replicas; i++) {
			for (int j = 0; j < replicas; j++) {
				reps.set(i, reps.get(i).merge(reps.get(j)));
			}
		}
		for (CanvasDoc d : reps) {
			assertThat(d).isEqualTo(canonical);
		}
	}

	@Property(tries = 100)
	void documentMergeIsCommutativeAssociativeIdempotent(@ForAll("opLogs") List<CanvasOp> ops) {
		int n = ops.size();
		CanvasDoc a = new CanvasDoc().applyAll(ops.subList(0, n / 3));
		CanvasDoc b = new CanvasDoc().applyAll(ops.subList(n / 3, 2 * n / 3));
		CanvasDoc c = new CanvasDoc().applyAll(ops.subList(2 * n / 3, n));
		assertThat(a.merge(a)).isEqualTo(a);                            // idempotent
		assertThat(a.merge(b)).isEqualTo(b.merge(a));                   // commutative
		assertThat(a.merge(b).merge(c)).isEqualTo(a.merge(b.merge(c))); // associative
	}
}
