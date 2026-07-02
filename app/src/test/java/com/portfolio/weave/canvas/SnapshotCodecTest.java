package com.portfolio.weave.canvas;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.weave.crdt.CanvasDoc;
import com.portfolio.weave.crdt.CanvasOp;
import com.portfolio.weave.crdt.Hlc;
import com.portfolio.weave.crdt.ShapeType;
import com.portfolio.weave.crdt.Timestamp;
import com.portfolio.weave.crdt.Vec2;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The snapshot codec must be lossless at the CRDT level — timestamps and tombstones included —
 * because the rebuilt document keeps merging with ops that arrive after the snapshot was cut.
 * A flattened (view-style) snapshot would silently break LWW arbitration on the next conflict.
 */
class SnapshotCodecTest {

	private final ObjectMapper mapper = JsonMapper.builder().build();
	private final SnapshotCodec codec = new SnapshotCodec(mapper);

	private static Timestamp ts(long l, int c, String actor) {
		return new Timestamp(new Hlc(l, c), actor);
	}

	@Test
	void aRealisticDocumentRoundTripsLosslessly() {
		UUID sticky = UUID.randomUUID();
		UUID victim = UUID.randomUUID();
		CanvasDoc doc = new CanvasDoc().applyAll(List.of(
				new CanvasOp.Create(sticky, ShapeType.STICKY, new Vec2(10, 20), new Vec2(180, 120),
						"#ffd54f", "메모 📝 with unicode", "a3", ts(1, 0, "alice")),
				CanvasOp.Set.position(sticky, new Vec2(300.5, -42.25), ts(2, 1, "bob")),
				new CanvasOp.Create(victim, ShapeType.RECT, new Vec2(0, 0), new Vec2(40, 40),
						"#111111", null, "a1", ts(3, 0, "alice")),
				new CanvasOp.Delete(victim, ts(4, 0, "bob"))));

		CanvasDoc rebuilt = codec.fromJson(codec.toJson(doc));

		assertThat(rebuilt).isEqualTo(doc);
		// and the tombstone really is still there (present in the map, excluded from live)
		assertThat(rebuilt.shapes()).hasSize(2);
		assertThat(rebuilt.liveShapes()).hasSize(1);
	}

	@Test
	void aPartialStateFromASetBeforeItsCreateRoundTrips() {
		// out-of-order delivery leaves a shape whose only known field is position — every
		// other register is still at bottom (null), which the codec must preserve
		UUID id = UUID.randomUUID();
		CanvasDoc doc = new CanvasDoc().apply(CanvasOp.Set.position(id, new Vec2(7, 8), ts(9, 0, "c")));

		CanvasDoc rebuilt = codec.fromJson(codec.toJson(doc));

		assertThat(rebuilt).isEqualTo(doc);
		assertThat(rebuilt.shapes().get(id).type()).isNull();
		assertThat(rebuilt.liveShapes()).isEmpty(); // no CREATE observed yet -> not drawable
	}

	@Test
	void aRegisterHoldingNullIsDistinctFromAnAbsentRegister() {
		// CREATE with a null text: the text register EXISTS and holds null at ts — after a
		// round-trip it must still beat an older concurrent write (absent would lose that).
		UUID id = UUID.randomUUID();
		CanvasDoc doc = new CanvasDoc().apply(new CanvasOp.Create(id, ShapeType.RECT, new Vec2(1, 1),
				new Vec2(2, 2), "#fff", null, "a0", ts(5, 0, "a")));

		CanvasDoc rebuilt = codec.fromJson(codec.toJson(doc));

		assertThat(rebuilt).isEqualTo(doc);
		assertThat(rebuilt.shapes().get(id).text()).isNotNull();
		assertThat(rebuilt.shapes().get(id).text().value()).isNull();
	}

	@Test
	void anEmptyDocumentRoundTrips() {
		assertThat(codec.fromJson(codec.toJson(new CanvasDoc()))).isEqualTo(new CanvasDoc());
	}
}
