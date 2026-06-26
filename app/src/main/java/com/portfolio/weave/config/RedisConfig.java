package com.portfolio.weave.config;

import com.portfolio.weave.canvas.RedisFanoutSubscriber;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Wires the Redis pub/sub listener that backs cross-instance op/cursor fan-out. Pattern
 * subscriptions ({@code weave.ops.*}, {@code weave.cursor.*}) mean we don't have to (un)subscribe
 * per room as clients come and go.
 */
@Configuration
public class RedisConfig {

	@Bean
	RedisMessageListenerContainer redisListenerContainer(RedisConnectionFactory connectionFactory,
	                                                     RedisFanoutSubscriber subscriber) {
		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(connectionFactory);
		container.addMessageListener(subscriber,
				List.of(new PatternTopic("weave.ops.*"), new PatternTopic("weave.cursor.*"), new PatternTopic("weave.text.*")));
		return container;
	}
}
