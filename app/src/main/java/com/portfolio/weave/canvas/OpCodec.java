package com.portfolio.weave.canvas;

import com.portfolio.weave.crdt.CanvasOp;
import com.portfolio.weave.crdt.Hlc;
import com.portfolio.weave.crdt.ShapeState;
import com.portfolio.weave.crdt.ShapeType;
import com.portfolio.weave.crdt.Timestamp;
import com.portfolio.weave.crdt.Vec2;
import com.portfolio.weave.persistence.CanvasOpEntity;
import com.portfolio.weave.persistence.OpType;
import com.portfolio.weave.web.Wire;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Translates a {@link CanvasOp} between its three representations: the wire DTO ({@link Wire.Op}),
 * the persisted op-log row ({@link CanvasOpEntity}), and the in-memory CRDT op. Keeping all the
 * mapping here means the convergence core ({@code crdt-core}) stays free of Jackson and JPA.
 */
@Component
public class OpCodec {

	private final ObjectMapper json;

	public OpCodec(ObjectMapper json) {
		this.json = json;
	}

	// The part of an op that varies by kind, serialised as the op-log row's jsonb payload.
	private record Payload(String shapeType, Wire.Vec position, Wire.Vec size, String color, String text,
	                       String z, Wire.Vec vec, String str) {
	}

	// ===== wire DTO -> CRDT op =====

	public CanvasOp fromDto(Wire.Op d) {
		Timestamp ts = new Timestamp(new Hlc(d.ts().l(), d.ts().c()), d.ts().actor());
		return switch (d.type()) {
			case "CREATE" -> new CanvasOp.Create(d.shapeId(), ShapeType.valueOf(d.shape().shapeType()),
					vec(d.shape().position()), vec(d.shape().size()), d.shape().color(), d.shape().text(),
					d.shape().z(), ts);
			case "SET" -> set(d.shapeId(), CanvasOp.Field.valueOf(d.field()), d.vec(), d.str(), ts);
			case "DELETE" -> new CanvasOp.Delete(d.shapeId(), ts);
			default -> throw new IllegalArgumentException("unknown op type: " + d.type());
		};
	}

	private CanvasOp set(UUID id, CanvasOp.Field field, Wire.Vec vec, String str, Timestamp ts) {
		return switch (field) {
			case POSITION -> CanvasOp.Set.position(id, vec(vec), ts);
			case SIZE -> CanvasOp.Set.size(id, vec(vec), ts);
			case COLOR -> CanvasOp.Set.color(id, str, ts);
			case TEXT -> CanvasOp.Set.text(id, str, ts);
			case Z -> CanvasOp.Set.z(id, str, ts);
		};
	}

	// ===== CRDT op -> op-log row =====

	public CanvasOpEntity toEntity(String roomId, CanvasOp op) {
		CanvasOpEntity e = new CanvasOpEntity();
		e.setId(UUID.randomUUID());
		e.setRoomId(roomId);
		e.setShapeId(op.shapeId());
		e.setHlcL(op.ts().hlc().l());
		e.setHlcC(op.ts().hlc().c());
		e.setActorId(op.ts().actorId());
		e.setCreatedAt(Instant.now());
		switch (op) {
			case CanvasOp.Create c -> {
				e.setOpType(OpType.CREATE);
				e.setPayload(write(new Payload(c.type().name(), vec(c.position()), vec(c.size()),
						c.color(), c.text(), c.z(), null, null)));
			}
			case CanvasOp.Set s -> {
				e.setOpType(OpType.SET);
				e.setField(s.field().name());
				e.setPayload(write(payloadForSet(s)));
			}
			case CanvasOp.Delete ignored -> {
				e.setOpType(OpType.DELETE);
				e.setPayload("{}");
			}
		}
		return e;
	}

	private Payload payloadForSet(CanvasOp.Set s) {
		return switch (s.field()) {
			case POSITION, SIZE -> new Payload(null, null, null, null, null, null, vec((Vec2) s.value()), null);
			case COLOR, TEXT, Z -> new Payload(null, null, null, null, null, null, null, (String) s.value());
		};
	}

	// ===== op-log row -> CRDT op (replay) =====

	public CanvasOp fromEntity(CanvasOpEntity e) {
		Timestamp ts = new Timestamp(new Hlc(e.getHlcL(), e.getHlcC()), e.getActorId());
		Payload p = read(e.getPayload());
		return switch (e.getOpType()) {
			case CREATE -> new CanvasOp.Create(e.getShapeId(), ShapeType.valueOf(p.shapeType()),
					vec(p.position()), vec(p.size()), p.color(), p.text(), p.z(), ts);
			case SET -> set(e.getShapeId(), CanvasOp.Field.valueOf(e.getField()), p.vec(), p.str(), ts);
			case DELETE -> new CanvasOp.Delete(e.getShapeId(), ts);
		};
	}

	// ===== CRDT shape state -> render-ready view (snapshots) =====

	public Wire.ShapeView toView(ShapeState s) {
		return new Wire.ShapeView(
				s.id(),
				s.type() != null ? s.type().value().name() : null,
				s.position() != null ? s.position().value().x() : 0,
				s.position() != null ? s.position().value().y() : 0,
				s.size() != null ? s.size().value().x() : 0,
				s.size() != null ? s.size().value().y() : 0,
				s.color() != null ? s.color().value() : null,
				s.text() != null ? s.text().value() : null,
				s.z() != null ? s.z().value() : null);
	}

	// ===== CRDT op -> wire DTO (time-travel history feed) =====

	public Wire.Op toDto(CanvasOp op) {
		Wire.Ts ts = new Wire.Ts(op.ts().hlc().l(), op.ts().hlc().c(), op.ts().actorId());
		return switch (op) {
			case CanvasOp.Create c -> new Wire.Op(c.shapeId(), "CREATE", ts,
					new Wire.Shape(c.type().name(), vec(c.position()), vec(c.size()), c.color(), c.text(), c.z()),
					null, null, null);
			case CanvasOp.Set s -> switch (s.field()) {
				case POSITION, SIZE -> new Wire.Op(s.shapeId(), "SET", ts, null, s.field().name(), vec((Vec2) s.value()), null);
				case COLOR, TEXT, Z -> new Wire.Op(s.shapeId(), "SET", ts, null, s.field().name(), null, (String) s.value());
			};
			case CanvasOp.Delete d -> new Wire.Op(d.shapeId(), "DELETE", ts, null, null, null, null);
		};
	}

	// --- helpers ---

	private static Vec2 vec(Wire.Vec v) {
		return v == null ? null : new Vec2(v.x(), v.y());
	}

	private static Wire.Vec vec(Vec2 v) {
		return v == null ? null : new Wire.Vec(v.x(), v.y());
	}

	private String write(Payload p) {
		return json.writeValueAsString(p);
	}

	private Payload read(String s) {
		return s == null || s.isBlank() ? null : json.readValue(s, Payload.class);
	}
}
