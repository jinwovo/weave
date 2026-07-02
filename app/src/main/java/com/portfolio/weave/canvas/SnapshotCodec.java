package com.portfolio.weave.canvas;

import com.portfolio.weave.crdt.CanvasDoc;
import com.portfolio.weave.crdt.Hlc;
import com.portfolio.weave.crdt.LwwRegister;
import com.portfolio.weave.crdt.ShapeState;
import com.portfolio.weave.crdt.ShapeType;
import com.portfolio.weave.crdt.Timestamp;
import com.portfolio.weave.crdt.Vec2;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Serialises a whole {@link CanvasDoc} to jsonb and back — the persisted form of a room
 * snapshot. Unlike {@link OpCodec#toView}, which flattens registers to plain values for
 * rendering, this codec is <em>lossless</em>: every register keeps its HLC timestamp and
 * tombstoned shapes stay present, because the rebuilt document must keep merging correctly
 * with ops that arrive after the snapshot was cut.
 *
 * <p>Each register is encoded as a (value, stamp) pair. A {@code null} stamp means the
 * register is absent (semilattice bottom — e.g. a SET observed before its CREATE); a present
 * stamp with a {@code null} value is a register that really holds null.
 */
@Component
public class SnapshotCodec {

	private final ObjectMapper json;

	public SnapshotCodec(ObjectMapper json) {
		this.json = json;
	}

	// --- the persisted document model ---

	private record Stamp(long l, int c, String a) {
	}

	private record SVec(double x, double y) {
	}

	private record SShape(
			UUID id,
			String type, Stamp typeTs,
			SVec position, Stamp positionTs,
			SVec size, Stamp sizeTs,
			String color, Stamp colorTs,
			String text, Stamp textTs,
			String z, Stamp zTs,
			Boolean deleted, Stamp deletedTs
	) {
	}

	private record SDoc(List<SShape> shapes) {
	}

	// --- CanvasDoc -> jsonb ---

	public String toJson(CanvasDoc doc) {
		List<SShape> shapes = doc.shapes().values().stream().map(SnapshotCodec::encode).toList();
		return json.writeValueAsString(new SDoc(shapes));
	}

	private static SShape encode(ShapeState s) {
		return new SShape(
				s.id(),
				s.type() == null || s.type().value() == null ? null : s.type().value().name(), stamp(s.type()),
				vec(s.position()), stamp(s.position()),
				vec(s.size()), stamp(s.size()),
				s.color() == null ? null : s.color().value(), stamp(s.color()),
				s.text() == null ? null : s.text().value(), stamp(s.text()),
				s.z() == null ? null : s.z().value(), stamp(s.z()),
				s.deleted() == null ? null : s.deleted().value(), stamp(s.deleted()));
	}

	private static Stamp stamp(LwwRegister<?> r) {
		return r == null ? null : new Stamp(r.ts().hlc().l(), r.ts().hlc().c(), r.ts().actorId());
	}

	private static SVec vec(LwwRegister<Vec2> r) {
		return r == null || r.value() == null ? null : new SVec(r.value().x(), r.value().y());
	}

	// --- jsonb -> CanvasDoc ---

	public CanvasDoc fromJson(String state) {
		SDoc doc = json.readValue(state, SDoc.class);
		Map<UUID, ShapeState> shapes = new LinkedHashMap<>();
		for (SShape s : doc.shapes()) {
			shapes.put(s.id(), decode(s));
		}
		return CanvasDoc.of(shapes);
	}

	private static ShapeState decode(SShape s) {
		return new ShapeState(
				s.id(),
				reg(s.type() == null ? null : ShapeType.valueOf(s.type()), s.typeTs()),
				reg(s.position() == null ? null : new Vec2(s.position().x(), s.position().y()), s.positionTs()),
				reg(s.size() == null ? null : new Vec2(s.size().x(), s.size().y()), s.sizeTs()),
				reg(s.color(), s.colorTs()),
				reg(s.text(), s.textTs()),
				reg(s.z(), s.zTs()),
				reg(s.deleted(), s.deletedTs()));
	}

	private static <T> LwwRegister<T> reg(T value, Stamp ts) {
		return ts == null ? null : LwwRegister.of(value, new Timestamp(new Hlc(ts.l(), ts.c()), ts.a()));
	}
}
