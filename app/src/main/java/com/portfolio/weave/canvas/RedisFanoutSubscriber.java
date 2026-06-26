package com.portfolio.weave.canvas;

import java.nio.charset.StandardCharsets;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * Receives ops/cursors published to {@code weave.ops.*} / {@code weave.cursor.*} by any instance
 * (including this one) and dispatches them: ops go through {@link CanvasService} (merge + relay),
 * cursors are relayed straight to local sockets. Making the Redis echo the single broadcast path
 * means the originating instance treats its own publications exactly like remote ones — uniform
 * and idempotent.
 */
@Component
public class RedisFanoutSubscriber implements MessageListener {

	private final CanvasService canvas;
	private final RoomRegistry registry;

	public RedisFanoutSubscriber(CanvasService canvas, RoomRegistry registry) {
		this.canvas = canvas;
		this.registry = registry;
	}

	@Override
	public void onMessage(Message message, byte[] pattern) {
		String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
		String body = new String(message.getBody(), StandardCharsets.UTF_8);
		if (channel.startsWith(RedisOpBus.OPS_PREFIX)) {
			canvas.onRemoteOp(channel.substring(RedisOpBus.OPS_PREFIX.length()), body);
		} else if (channel.startsWith(RedisOpBus.CURSOR_PREFIX)) {
			registry.broadcast(channel.substring(RedisOpBus.CURSOR_PREFIX.length()), body);
		} else if (channel.startsWith(RedisOpBus.TEXT_PREFIX)) {
			canvas.onRemoteText(channel.substring(RedisOpBus.TEXT_PREFIX.length()), body);
		}
	}
}
