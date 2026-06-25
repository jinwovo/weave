package com.portfolio.weave.canvas;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.weave.crdt.CanvasOp;
import com.portfolio.weave.crdt.Hlc;
import com.portfolio.weave.crdt.ShapeType;
import com.portfolio.weave.crdt.Timestamp;
import com.portfolio.weave.crdt.Vec2;
import com.portfolio.weave.persistence.CanvasOpEntity;
import com.portfolio.weave.web.Wire;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The codec is the only place a CanvasOp loses/regains its type identity (wire ⇄ jsonb row ⇄ CRDT
 * op). If a round-trip isn't faithful, replay would silently reconstruct the wrong board — so pin
 * each op kind down with structural equality (records give us that for free).
 */
class OpCodecTest {

	private final ObjectMapper mapper = JsonMapper.builder().build();
	private final OpCodec codec = new OpCodec(mapper);

	private static Timestamp ts(long l, int c, String actor) {
		return new Timestamp(new Hlc(l, c), actor);
	}

	@Test
	void createRoundTripsThroughTheOpLogRow() {
		UUID id = UUID.randomUUID();
		CanvasOp.Create op = new CanvasOp.Create(id, ShapeType.STICKY, new Vec2(10, 20), new Vec2(100, 80),
				"#abcdef", "hello", "z1", ts(5, 2, "a"));

		CanvasOpEntity row = codec.toEntity("room-1", op);
		assertThat(codec.fromEntity(row)).isEqualTo(op);
	}

	@Test
	void setPositionRoundTrips() {
		UUID id = UUID.randomUUID();
		CanvasOp op = CanvasOp.Set.position(id, new Vec2(250, 180), ts(7, 0, "b"));
		assertThat(codec.fromEntity(codec.toEntity("room-1", op))).isEqualTo(op);
	}

	@Test
	void setColorRoundTrips() {
		UUID id = UUID.randomUUID();
		CanvasOp op = CanvasOp.Set.color(id, "#00ff00", ts(8, 0, "b"));
		assertThat(codec.fromEntity(codec.toEntity("room-1", op))).isEqualTo(op);
	}

	@Test
	void deleteRoundTrips() {
		UUID id = UUID.randomUUID();
		CanvasOp op = new CanvasOp.Delete(id, ts(9, 1, "c"));
		assertThat(codec.fromEntity(codec.toEntity("room-1", op))).isEqualTo(op);
	}

	@Test
	void wireDtoMapsToTheSameOpAsTheRow() {
		UUID id = UUID.randomUUID();
		Wire.Op dto = new Wire.Op(id, "SET", new Wire.Ts(3, 0, "a"), null, "POSITION",
				new Wire.Vec(42, 43), null);
		assertThat(codec.fromDto(dto)).isEqualTo(CanvasOp.Set.position(id, new Vec2(42, 43), ts(3, 0, "a")));
	}
}
