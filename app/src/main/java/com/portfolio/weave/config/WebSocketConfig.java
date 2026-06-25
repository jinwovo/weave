package com.portfolio.weave.config;

import com.portfolio.weave.web.WeaveWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Exposes the raw WebSocket endpoint at {@code /ws?room=<id>&actor=<id>}. Origins are open for the
 * local demo; tighten before any public deployment.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

	private final WeaveWebSocketHandler handler;

	public WebSocketConfig(WeaveWebSocketHandler handler) {
		this.handler = handler;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(handler, "/ws").setAllowedOriginPatterns("*");
	}

	/** Raise the inbound text-frame limit so pasted image data URLs fit (default is only 8 KB). */
	@Bean
	ServletServerContainerFactoryBean webSocketContainer() {
		ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
		container.setMaxTextMessageBufferSize(4 * 1024 * 1024);
		return container;
	}
}
