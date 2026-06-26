package com.portfolio.weave.canvas;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes ops and cursors onto per-room Redis channels so that every app instance — not just the
 * one that received the WebSocket frame — can relay them to its local sessions. This is what makes
 * the relay horizontally scalable; a single instance simply subscribes to its own publications.
 */
@Component
public class RedisOpBus {

	static final String OPS_PREFIX = "weave.ops.";
	static final String CURSOR_PREFIX = "weave.cursor.";
	static final String TEXT_PREFIX = "weave.text.";

	private final StringRedisTemplate redis;

	public RedisOpBus(StringRedisTemplate redis) {
		this.redis = redis;
	}

	public void publishOp(String room, String json) {
		redis.convertAndSend(OPS_PREFIX + room, json);
	}

	public void publishCursor(String room, String json) {
		redis.convertAndSend(CURSOR_PREFIX + room, json);
	}

	public void publishText(String room, String json) {
		redis.convertAndSend(TEXT_PREFIX + room, json);
	}
}
