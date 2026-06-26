package com.portfolio.weave.canvas;

import com.portfolio.weave.crdt.CanvasDoc;
import com.portfolio.weave.crdt.CanvasOp;
import com.portfolio.weave.persistence.CanvasOpEntity;
import com.portfolio.weave.persistence.CanvasOpRepository;
import com.portfolio.weave.persistence.TextOpEntity;
import com.portfolio.weave.persistence.TextOpRepository;
import com.portfolio.weave.web.Wire;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * The heart of the sync server. It owns, per room, an in-memory {@link CanvasDoc} (rebuilt by
 * replaying the op-log through {@code crdt-core}) and mediates three flows:
 *
 * <ul>
 *   <li><b>ingest</b> — a local client's op is persisted idempotently, then published to Redis;</li>
 *   <li><b>onRemoteOp</b> — an op arriving from Redis (possibly from another instance) is applied
 *       to the in-memory doc and relayed to local sockets;</li>
 *   <li><b>snapshot</b> — a joining client receives the whole converged board at once.</li>
 * </ul>
 *
 * The server is never the source of truth: it is a durable, fan-out wrapper around the CRDT.
 */
@Service
public class CanvasService {

	private final CanvasOpRepository repo;
	private final TextOpRepository textRepo;
	private final OpCodec codec;
	private final ObjectMapper mapper;
	private final RedisOpBus bus;
	private final RoomRegistry registry;
	private final Counter opsIngested;
	private final Counter textOpsIngested;
	private final Timer ingestTimer;

	private final Map<String, AtomicReference<CanvasDoc>> docs = new ConcurrentHashMap<>();

	public CanvasService(CanvasOpRepository repo, TextOpRepository textRepo, OpCodec codec, ObjectMapper mapper,
	                     RedisOpBus bus, RoomRegistry registry, MeterRegistry meters) {
		this.repo = repo;
		this.textRepo = textRepo;
		this.codec = codec;
		this.mapper = mapper;
		this.bus = bus;
		this.registry = registry;
		this.opsIngested = Counter.builder("weave.ops.ingested").description("canvas ops persisted").register(meters);
		this.textOpsIngested = Counter.builder("weave.text.ops.ingested").description("text ops persisted").register(meters);
		this.ingestTimer = Timer.builder("weave.ingest").description("op persist latency").register(meters);
	}

	/** The full converged board for a joining client, shapes ordered by their z register. */
	public Wire.Snapshot snapshot(String room) {
		List<Wire.ShapeView> shapes = roomDoc(room).get().liveShapes().stream()
				.map(codec::toView)
				.sorted(Comparator.comparing(v -> v.z() == null ? "" : v.z()))
				.toList();
		return new Wire.Snapshot("snapshot", shapes);
	}

	/** A local client authored an op: persist it idempotently, then fan it out (only if it was new). */
	public void ingest(String room, Wire.Op dto) {
		CanvasOp op = codec.fromDto(dto);
		if (ingestTimer.record(() -> persist(room, op))) {
			opsIngested.increment();
			bus.publishOp(room, mapper.writeValueAsString(new Wire.OpBroadcast("op", dto)));
		}
	}

	/** An op arrived over Redis: merge it into this instance's doc and relay to local sockets. */
	public void onRemoteOp(String room, String body) {
		Wire.OpBroadcast broadcast = mapper.readValue(body, Wire.OpBroadcast.class);
		apply(room, codec.fromDto(broadcast.op()));
		registry.broadcast(room, body);
	}

	/** An ephemeral cursor update: fan out over Redis, never persisted, never applied to the doc. */
	public void cursor(String room, String actor, Double x, Double y) {
		if (x == null || y == null) {
			return;
		}
		bus.publishCursor(room, mapper.writeValueAsString(new Wire.Cursor("cursor", actor, x, y)));
	}

	/** A local client authored an RGA text op on a shape body: persist idempotently, then fan out if new. */
	public void ingestText(String room, UUID shapeId, Wire.TextOp op) {
		if (ingestTimer.record(() -> persistText(room, shapeId, op))) {
			textOpsIngested.increment();
			bus.publishText(room, mapper.writeValueAsString(new Wire.TextBroadcast("text", shapeId, op)));
		}
	}

	/** A text op arrived over Redis: relay it to local sockets. RGA convergence runs on the clients. */
	public void onRemoteText(String room, String body) {
		registry.broadcast(room, body);
	}

	// --- internals ---

	private boolean persistText(String room, UUID shapeId, Wire.TextOp op) {
		TextOpEntity e = new TextOpEntity();
		e.setId(UUID.randomUUID());
		e.setRoomId(room);
		e.setShapeId(shapeId);
		e.setOpType(op.type());
		e.setOpKey(keyOf(op));
		e.setPayload(mapper.writeValueAsString(op));
		e.setCreatedAt(Instant.now());
		try {
			textRepo.saveAndFlush(e);
			return true;
		} catch (DataIntegrityViolationException duplicate) {
			return false; // same element insert / target delete already stored — idempotent
		}
	}

	private static String keyOf(Wire.TextOp op) {
		Wire.Ts t = "INSERT".equals(op.type()) ? op.id() : op.target();
		return t.l() + ":" + t.c() + ":" + t.actor();
	}

	private boolean persist(String room, CanvasOp op) {
		try {
			repo.saveAndFlush(codec.toEntity(room, op));
			return true;
		} catch (DataIntegrityViolationException duplicate) {
			// (room, actor, hlc) already present — a retried/echoed op. Idempotent by construction.
			return false;
		}
	}

	private AtomicReference<CanvasDoc> roomDoc(String room) {
		return docs.computeIfAbsent(room, r -> new AtomicReference<>(replay(r)));
	}

	private CanvasDoc replay(String room) {
		CanvasDoc doc = new CanvasDoc();
		for (CanvasOpEntity e : repo.findByRoomIdOrderByHlcLAscHlcCAscActorIdAsc(room)) {
			doc = doc.apply(codec.fromEntity(e));
		}
		return doc;
	}

	private void apply(String room, CanvasOp op) {
		roomDoc(room).updateAndGet(doc -> doc.apply(op));
	}
}
