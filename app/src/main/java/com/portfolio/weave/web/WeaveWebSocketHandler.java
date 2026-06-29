package com.portfolio.weave.web;

import com.portfolio.weave.canvas.CanvasService;
import com.portfolio.weave.canvas.RoomRegistry;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * The WebSocket endpoint. On connect it registers the session and pushes a {@link Wire.Snapshot};
 * thereafter it forwards client ops/cursors into {@link CanvasService} and lets the Redis fan-out
 * path deliver everything (including the sender's own ops) back to the room. The handler itself is
 * deliberately thin — all convergence logic lives below it.
 */
@Component
public class WeaveWebSocketHandler extends TextWebSocketHandler {

	private final ObjectMapper mapper;
	private final CanvasService canvas;
	private final RoomRegistry registry;

	public WeaveWebSocketHandler(ObjectMapper mapper, CanvasService canvas, RoomRegistry registry) {
		this.mapper = mapper;
		this.canvas = canvas;
		this.registry = registry;
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) throws Exception {
		var params = UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams();
		// query params arrive percent-encoded (e.g. the hour bucket "base@123" -> "base%40123");
		// decode so the room id matches what the HTTP history/epochs endpoints (auto-decoded) use.
		String room = orDefault(decode(params.getFirst("room")), "default");
		String actor = orDefault(decode(params.getFirst("actor")), "anon-" + session.getId());

		WebSocketSession out = registry.add(room, actor, session);
		out.sendMessage(new TextMessage(mapper.writeValueAsString(canvas.snapshot(room))));
		broadcastPresence(room);
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) {
		RoomRegistry.SessionInfo info = registry.info(session.getId());
		if (info == null) {
			return;
		}
		Wire.Inbound in;
		try {
			in = mapper.readValue(message.getPayload(), Wire.Inbound.class);
		} catch (RuntimeException malformed) {
			return; // ignore a junk frame rather than tearing down the socket
		}
		if ("op".equals(in.kind()) && in.op() != null) {
			// bind the op to the authenticated session actor (prevents id spoofing / LWW corruption)
			canvas.ingest(info.room(), withActor(in.op(), info.actor()));
		} else if ("cursor".equals(in.kind())) {
			canvas.cursor(info.room(), info.actor(), in.x(), in.y());
		} else if ("draft".equals(in.kind())) {
			canvas.draft(info.room(), info.actor(), in.draft());
		} else if ("text".equals(in.kind()) && in.textOp() != null && in.textShapeId() != null) {
			canvas.ingestText(info.room(), in.textShapeId(), in.textOp());
		}
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
		RoomRegistry.SessionInfo info = registry.remove(session.getId());
		if (info != null) {
			broadcastPresence(info.room());
		}
	}

	private void broadcastPresence(String room) {
		registry.broadcast(room, mapper.writeValueAsString(new Wire.Presence("presence", registry.actors(room))));
	}

	private static Wire.Op withActor(Wire.Op op, String actor) {
		Wire.Ts ts = new Wire.Ts(op.ts().l(), op.ts().c(), actor);
		return new Wire.Op(op.shapeId(), op.type(), ts, op.shape(), op.field(), op.vec(), op.str());
	}

	private static String orDefault(String value, String fallback) {
		return (value == null || value.isBlank()) ? fallback : value;
	}

	private static String decode(String value) {
		return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
	}
}
