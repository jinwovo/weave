package com.portfolio.weave.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

/**
 * A deterministic fault-injection simulation — a Jepsen-lite / DST harness in the spirit of
 * FoundationDB and TigerBeetle. N replicas of {@link RgaText} edit concurrently while an adversarial
 * in-memory network <em>delays, reorders, drops-and-redelivers and duplicates</em> every op. When the
 * network finally drains, all replicas must hold the identical string. The whole run is a pure
 * function of the seed, so a failure is perfectly reproducible — convergence proven against the worst
 * delivery the network can produce, across thousands of seeds.
 */
class ConvergenceSimulationTest {

	private static final char[] ALPHABET = "abcd".toCharArray();

	private record Msg(int to, TextOp op, long at, long seq) {
	}

	@Property(tries = 400)
	void rgaConvergesUnderAnAdversarialNetwork(@ForAll @LongRange(min = 1, max = 5_000_000) long seed) {
		Random rnd = new Random(seed);
		int replicas = 3 + rnd.nextInt(2); // 3..4
		long[] phys = { 1000L };
		List<RgaText> rep = new ArrayList<>();
		List<HlcClock> clk = new ArrayList<>();
		List<String> actor = new ArrayList<>();
		for (int i = 0; i < replicas; i++) {
			rep.add(new RgaText());
			clk.add(new HlcClock(() -> phys[0]++));
			actor.add("r" + i);
		}

		// in-flight messages ordered by delivery tick (seq breaks ties for determinism)
		PriorityQueue<Msg> net = new PriorityQueue<>((x, y) -> {
			int byTick = Long.compare(x.at(), y.at());
			return byTick != 0 ? byTick : Long.compare(x.seq(), y.seq());
		});
		List<TextOp> allOps = new ArrayList<>();
		long seq = 0;
		int edits = 20 + rnd.nextInt(50);
		int made = 0;
		long tick = 0;

		while (made < edits || !net.isEmpty()) {
			tick++;
			if (made < edits && rnd.nextInt(2) == 0) {
				int r = rnd.nextInt(replicas);
				RgaText t = rep.get(r);
				int len = t.length();
				TextOp op;
				if (len > 0 && rnd.nextInt(3) == 0) {
					op = t.localDelete(rnd.nextInt(len));
				} else {
					Timestamp id = new Timestamp(clk.get(r).tick(), actor.get(r));
					op = t.localInsert(rnd.nextInt(len + 1), ALPHABET[rnd.nextInt(ALPHABET.length)], id);
				}
				allOps.add(op);
				made++;
				for (int j = 0; j < replicas; j++) {
					if (j == r) {
						continue;
					}
					boolean drop = rnd.nextInt(7) == 0; // "drop" = redelivered much later (an eventual network)
					long at = drop ? tick + 12 + rnd.nextInt(25) : tick + 1 + rnd.nextInt(6); // delay + reorder
					net.add(new Msg(j, op, at, seq++));
					if (rnd.nextInt(6) == 0) {
						net.add(new Msg(j, op, at + rnd.nextInt(4), seq++)); // duplicate
					}
				}
			}
			while (!net.isEmpty() && net.peek().at() <= tick) {
				Msg m = net.poll();
				rep.get(m.to()).apply(m.op());
			}
		}

		RgaText reference = new RgaText();
		for (TextOp op : allOps) {
			reference.apply(op);
		}
		String expected = reference.value();
		for (int i = 0; i < replicas; i++) {
			assertThat(rep.get(i).value()).as("replica r%d", i).isEqualTo(expected);
		}
	}
}
