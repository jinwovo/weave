package com.portfolio.weave.canvas;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * Tracks the live WebSocket sessions per room on <em>this</em> instance and fans a text frame out
 * to them. Sessions are wrapped in a {@link ConcurrentWebSocketSessionDecorator} so the relay
 * thread and the Redis-subscriber thread can never interleave a partial write to the same socket.
 */
@Component
public class RoomRegistry {

	public record SessionInfo(String room, String actor, WebSocketSession session) {
	}

	private final Map<String, SessionInfo> byId = new ConcurrentHashMap<>();
	private final Map<String, Set<String>> roomToIds = new ConcurrentHashMap<>();

	/** Register a freshly-connected session and return the thread-safe handle to send through. */
	public WebSocketSession add(String room, String actor, WebSocketSession raw) {
		WebSocketSession out = new ConcurrentWebSocketSessionDecorator(raw, 1000, 64 * 1024);
		byId.put(raw.getId(), new SessionInfo(room, actor, out));
		roomToIds.computeIfAbsent(room, r -> ConcurrentHashMap.newKeySet()).add(raw.getId());
		return out;
	}

	public SessionInfo info(String sessionId) {
		return byId.get(sessionId);
	}

	public SessionInfo remove(String sessionId) {
		SessionInfo info = byId.remove(sessionId);
		if (info != null) {
			Set<String> ids = roomToIds.get(info.room());
			if (ids != null) {
				ids.remove(sessionId);
				if (ids.isEmpty()) {
					roomToIds.remove(info.room());
				}
			}
		}
		return info;
	}

	public List<String> actors(String room) {
		return roomToIds.getOrDefault(room, Set.of()).stream()
				.map(byId::get)
				.filter(Objects::nonNull)
				.map(SessionInfo::actor)
				.distinct()
				.sorted()
				.toList();
	}

	public void broadcast(String room, String text) {
		TextMessage frame = new TextMessage(text);
		for (String id : roomToIds.getOrDefault(room, Set.of())) {
			SessionInfo info = byId.get(id);
			if (info != null && info.session().isOpen()) {
				try {
					info.session().sendMessage(frame);
				} catch (IOException broken) {
					// a dead socket will be cleaned up by afterConnectionClosed; drop this frame
				}
			}
		}
	}
}
