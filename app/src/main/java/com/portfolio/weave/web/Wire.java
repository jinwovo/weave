package com.portfolio.weave.web;

import java.util.List;
import java.util.UUID;

/**
 * The JSON protocol spoken over the WebSocket, in one place. Every message is tagged with a
 * {@code kind} so a single text channel multiplexes ops, presence and ephemeral cursors.
 *
 * <p>Client → server: {@link Inbound} (kind {@code "op"} carries {@link Op}; {@code "cursor"}
 * carries x/y). Server → client: {@link Snapshot} on join, {@link OpBroadcast} per op,
 * {@link Cursor} for live cursors, {@link Presence} when the roster changes.
 */
public final class Wire {

	private Wire() {
	}

	/** A Hybrid Logical Clock timestamp as it appears on the wire. */
	public record Ts(long l, int c, String actor) {
	}

	/** A 2D vector on the wire (position or size). */
	public record Vec(double x, double y) {
	}

	/** The properties carried by a CREATE op. */
	public record Shape(String shapeType, Vec position, Vec size, String color, String text, String z) {
	}

	/**
	 * One operation. {@code type} is CREATE/SET/DELETE. For CREATE, {@link #shape} is set; for
	 * SET, {@link #field} plus exactly one of {@link #vec}/{@link #str}; for DELETE, neither.
	 */
	public record Op(UUID shapeId, String type, Ts ts, Shape shape, String field, Vec vec, String str) {
	}

	/**
	 * One RGA text operation on a shape's body. {@code type} is INSERT/DELETE. For INSERT, {@link #id}
	 * is the new character element, {@link #origin} the element it follows (null = start), {@link #ch}
	 * the character; for DELETE, {@link #target} is the element to tombstone. The server relays and
	 * persists these opaquely — the RGA convergence runs on the clients.
	 */
	public record TextOp(String type, Ts id, Ts origin, String ch, Ts target) {
	}

	/** Client → server envelope. Carries {@link Op} for "op", x/y for "cursor", text fields for "text". */
	public record Inbound(String kind, Op op, Double x, Double y, UUID textShapeId, TextOp textOp) {
	}

	/** Server → client: a single op to merge into the local document. */
	public record OpBroadcast(String kind, Op op) {
	}

	/** Server → client: someone's live cursor (ephemeral; never persisted). */
	public record Cursor(String kind, String actor, double x, double y) {
	}

	/** Server → client: the current roster of actors in the room. */
	public record Presence(String kind, List<String> actors) {
	}

	/** A flattened, render-ready shape (live registers resolved to plain values). */
	public record ShapeView(UUID id, String shapeType, double x, double y, double w, double h,
	                        String color, String text, String z) {
	}

	/** Server → client: the full current board, sent once when a client joins. */
	public record Snapshot(String kind, List<ShapeView> shapes) {
	}

	/** Server → client: a single text op to merge into the shape's local RGA. */
	public record TextBroadcast(String kind, UUID shapeId, TextOp op) {
	}

	/** One entry of a room's text-op history (fetched on join to rebuild the per-shape RGAs). */
	public record TextHistoryItem(UUID shapeId, TextOp op) {
	}
}
