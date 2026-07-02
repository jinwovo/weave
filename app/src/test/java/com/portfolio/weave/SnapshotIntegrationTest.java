package com.portfolio.weave;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.weave.canvas.CanvasService;
import com.portfolio.weave.canvas.OpCodec;
import com.portfolio.weave.canvas.SnapshotStore;
import com.portfolio.weave.crdt.CanvasDoc;
import com.portfolio.weave.persistence.CanvasOpEntity;
import com.portfolio.weave.persistence.CanvasOpRepository;
import com.portfolio.weave.persistence.CanvasSnapshotEntity;
import com.portfolio.weave.persistence.CanvasSnapshotRepository;
import com.portfolio.weave.web.Wire;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves snapshot-accelerated replay end-to-end against real Postgres: the sweeper finds the
 * room, the persisted snapshot bounds the cold-start tail, and — the part that matters — the
 * bounded rebuild equals the full-log fold exactly, including after an idempotent re-refresh.
 */
// RANDOM_PORT: WebSocketConfig needs a real servlet container (the mock environment has no
// jakarta.websocket ServerContainer to configure)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"weave.snapshot.threshold=5",
		"weave.snapshot.grace=PT0S",     // tests ingest then wait; no in-flight transactions here
		"weave.snapshot.sweep-delay=PT1H" // the test drives sweep()/refresh() by hand
})
@Testcontainers
class SnapshotIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Container
	@ServiceConnection(name = "redis")
	static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379);

	@Autowired
	CanvasService service;

	@Autowired
	SnapshotStore store;

	@Autowired
	CanvasOpRepository repo;

	@Autowired
	CanvasSnapshotRepository snapshots;

	@Autowired
	OpCodec opCodec;

	@Test
	void sweeperSnapshotsTheRoomAndColdStartFoldsOnlyTheTail() throws Exception {
		String room = "snap-room";
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();

		// a realistic burst: two creates, edits on both, one delete (12 ops > threshold 5)
		long l = 1;
		service.ingest(room, create(a, "alice", l++));
		service.ingest(room, create(b, "alice", l++));
		for (int i = 0; i < 4; i++) {
			service.ingest(room, setPos(a, "alice", l++, 100 + i, 50));
			service.ingest(room, setColor(b, "bob", l++, "#00b8" + i + "0"));
		}
		service.ingest(room, setPos(b, "bob", l++, 640, 480));
		service.ingest(room, delete(a, "bob", l++));
		assertThat(repo.countByRoomId(room)).isEqualTo(12);

		// the sweeper (driven by hand — same code the scheduler runs) snapshots the room
		Thread.sleep(50); // step past the PT0S grace horizon's clock granularity
		store.sweep();
		CanvasSnapshotEntity snap = snapshots.findById(room).orElseThrow();
		assertThat(snap.getOpCount()).isEqualTo(12);
		assertThat(snap.getShapeCount()).isEqualTo(2); // tombstoned shape still present ...
		CanvasDoc fromSnapshotOnly = store.load(room).doc();
		assertThat(fromSnapshotOnly.liveShapes()).hasSize(1); // ... but not drawable

		// ops keep arriving after the snapshot was cut
		service.ingest(room, setPos(b, "alice", l++, 10, 10));
		service.ingest(room, setColor(b, "alice", l++, "#123456"));
		service.ingest(room, create(a, "alice", l++)); // resurrect the deleted shape (LWW)

		// cold start folds ONLY the 3-op tail — and lands on exactly the full-log fold
		SnapshotStore.Loaded loaded = store.load(room);
		assertThat(loaded.fromSnapshot()).isTrue();
		assertThat(loaded.tailOps()).isEqualTo(3);
		assertThat(loaded.doc()).isEqualTo(fullFold(room));
		assertThat(loaded.doc().liveShapes()).hasSize(2); // resurrection survived the snapshot

		// refreshing again folds the tail into the snapshot; a second refresh is a no-op;
		// the rebuilt document never changes (idempotent, exact)
		Thread.sleep(50);
		store.refresh(room);
		store.refresh(room);
		CanvasSnapshotEntity advanced = snapshots.findById(room).orElseThrow();
		assertThat(advanced.getOpCount()).isEqualTo(15);
		assertThat(advanced.getUptoSeq()).isGreaterThan(snap.getUptoSeq());
		SnapshotStore.Loaded reloaded = store.load(room);
		assertThat(reloaded.tailOps()).isZero();
		assertThat(reloaded.doc()).isEqualTo(fullFold(room));
	}

	/** Ground truth: the whole op-log folded the way the pre-snapshot server did it. */
	private CanvasDoc fullFold(String room) {
		CanvasDoc doc = new CanvasDoc();
		for (CanvasOpEntity e : repo.findByRoomIdOrderByHlcLAscHlcCAscActorIdAsc(room)) {
			doc = doc.apply(opCodec.fromEntity(e));
		}
		return doc;
	}

	// --- op builders ---

	private Wire.Op create(UUID id, String actor, long l) {
		return new Wire.Op(id, "CREATE", new Wire.Ts(l, 0, actor),
				new Wire.Shape("RECT", new Wire.Vec(5, 5), new Wire.Vec(40, 40), "#ff0000", "", "a0"),
				null, null, null);
	}

	private Wire.Op setPos(UUID id, String actor, long l, double x, double y) {
		return new Wire.Op(id, "SET", new Wire.Ts(l, 0, actor), null, "POSITION", new Wire.Vec(x, y), null);
	}

	private Wire.Op setColor(UUID id, String actor, long l, String color) {
		return new Wire.Op(id, "SET", new Wire.Ts(l, 0, actor), null, "COLOR", null, color);
	}

	private Wire.Op delete(UUID id, String actor, long l) {
		return new Wire.Op(id, "DELETE", new Wire.Ts(l, 0, actor), null, null, null, null);
	}
}
