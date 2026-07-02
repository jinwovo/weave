package com.portfolio.weave.canvas;

import com.portfolio.weave.crdt.CanvasDoc;
import com.portfolio.weave.persistence.CanvasOpEntity;
import com.portfolio.weave.persistence.CanvasOpRepository;
import com.portfolio.weave.persistence.CanvasSnapshotEntity;
import com.portfolio.weave.persistence.CanvasSnapshotRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Bounds cold-start recovery. Without snapshots, rebuilding a room's in-memory document means
 * folding its entire op-log; with them, replay folds the persisted snapshot plus only the ops
 * appended since — O(tail), where the sweeper keeps the tail at roughly the refresh threshold.
 *
 * <p><b>Why this is exact, not approximate:</b> the document fold is a join-semilattice merge —
 * commutative, associative, idempotent — so folding any subset of ops and then the rest (in any
 * order, with any overlap) rebuilds the identical document. See
 * {@code CanvasDocPropertyTest.aSnapshotOfAnyPrefixPlusTheTailRebuildsTheExactDocument}.
 *
 * <p><b>The watermark race:</b> seq values are handed out before their inserting transactions
 * commit, so "max(seq) right now" may skip a row that commits a moment later. The builder
 * therefore only advances the watermark past ops older than a grace window ({@code grace}) —
 * ingest is a single-statement transaction, so a commit can only trail its seq by milliseconds
 * against a multi-second window. Rows inside the window are simply re-read next round; if one
 * was already folded, idempotency absorbs it.
 *
 * <p>Tombstones are kept in snapshots: dropping one could resurrect a deleted shape when an
 * older-stamped op arrives later (an offline client flushing its outbox hours after the fact).
 */
@Service
public class SnapshotStore {

	private static final Logger log = LoggerFactory.getLogger(SnapshotStore.class);

	/** A rebuilt room document plus how much work the rebuild actually did. */
	public record Loaded(CanvasDoc doc, long uptoSeq, int tailOps, boolean fromSnapshot) {
	}

	private final CanvasOpRepository ops;
	private final CanvasSnapshotRepository snapshots;
	private final OpCodec opCodec;
	private final SnapshotCodec codec;
	private final int threshold;
	private final Duration grace;
	private final Counter writes;
	private final Timer refreshTimer;
	private final Timer loadTimer;
	private final DistributionSummary tailFolded;

	public SnapshotStore(CanvasOpRepository ops, CanvasSnapshotRepository snapshots, OpCodec opCodec,
	                     SnapshotCodec codec, MeterRegistry meters,
	                     @Value("${weave.snapshot.threshold:500}") int threshold,
	                     @Value("${weave.snapshot.grace:PT10S}") Duration grace) {
		this.ops = ops;
		this.snapshots = snapshots;
		this.opCodec = opCodec;
		this.codec = codec;
		this.threshold = threshold;
		this.grace = grace;
		this.writes = Counter.builder("weave.snapshot.writes")
				.description("room snapshots written").register(meters);
		this.refreshTimer = Timer.builder("weave.snapshot.refresh")
				.description("snapshot build latency (tail fold + serialise + upsert)").register(meters);
		this.loadTimer = Timer.builder("weave.replay")
				.description("cold-start room rebuild latency").register(meters);
		this.tailFolded = DistributionSummary.builder("weave.replay.tail.ops")
				.description("ops folded beyond the snapshot at cold-start").register(meters);
	}

	/**
	 * Rebuild a room's document: persisted snapshot (if any) plus the full committed tail.
	 * The tail here deliberately ignores the grace horizon — a reader must see everything.
	 */
	public Loaded load(String room) {
		return loadTimer.record(() -> {
			CanvasDoc doc = new CanvasDoc();
			long upto = 0;
			CanvasSnapshotEntity snap = snapshots.findById(room).orElse(null);
			if (snap != null) {
				doc = codec.fromJson(snap.getState());
				upto = snap.getUptoSeq();
			}
			List<CanvasOpEntity> tail = ops.findByRoomIdAndSeqGreaterThanOrderBySeqAsc(room, upto);
			for (CanvasOpEntity e : tail) {
				doc = doc.apply(opCodec.fromEntity(e));
			}
			tailFolded.record(tail.size());
			long seenUpto = tail.isEmpty() ? upto : tail.getLast().getSeq();
			return new Loaded(doc, seenUpto, tail.size(), snap != null);
		});
	}

	/** Advance (or create) a room's snapshot by folding the tail beyond the grace horizon into it. */
	public void refresh(String room) {
		refreshTimer.record(() -> {
			CanvasSnapshotEntity snap = snapshots.findById(room).orElse(null);
			long upto = snap == null ? 0 : snap.getUptoSeq();
			List<CanvasOpEntity> tail = ops.tailForSnapshot(room, upto, Instant.now().minus(grace));
			if (tail.isEmpty()) {
				return;
			}
			CanvasDoc doc = snap == null ? new CanvasDoc() : codec.fromJson(snap.getState());
			for (CanvasOpEntity e : tail) {
				doc = doc.apply(opCodec.fromEntity(e));
			}
			CanvasSnapshotEntity next = snap == null ? new CanvasSnapshotEntity() : snap;
			next.setRoomId(room);
			next.setUptoSeq(tail.getLast().getSeq());
			next.setState(codec.toJson(doc));
			next.setShapeCount(doc.shapes().size());
			next.setOpCount((snap == null ? 0 : snap.getOpCount()) + tail.size());
			next.setUpdatedAt(Instant.now());
			snapshots.save(next);
			writes.increment();
			log.debug("snapshot {} advanced to seq {} ({} ops folded, {} shapes)",
					room, next.getUptoSeq(), tail.size(), next.getShapeCount());
		});
	}

	/**
	 * Refresh every room whose un-snapshotted tail outgrew the threshold. Runs on every
	 * instance; concurrent refreshes of the same room race benignly (each upserts an
	 * internally-consistent snapshot, and the fold is idempotent either way). Note the tail
	 * only grows while some instance is alive to ingest — so the sweep cadence, not downtime,
	 * bounds how large a cold-start tail can get.
	 */
	@Scheduled(fixedDelayString = "${weave.snapshot.sweep-delay:PT30S}", initialDelayString = "${weave.snapshot.sweep-delay:PT30S}")
	public void sweep() {
		for (String room : ops.roomsWithTailAtLeast(threshold)) {
			try {
				refresh(room);
			} catch (Exception e) {
				log.warn("snapshot refresh failed for room {} — will retry next sweep", room, e);
			}
		}
	}
}
