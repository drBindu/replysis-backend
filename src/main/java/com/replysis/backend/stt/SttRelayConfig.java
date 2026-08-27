package com.replysis.backend.stt;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Where the speech relay listens.
 *
 * Under /api/v1/ deliberately: that prefix already has an nginx location block
 * and is already reachable from every network the app works on at all. A new
 * top-level path would need its own proxy rule and would be the first thing to
 * be wrong on a network we cannot test from.
 *
 * Origins are not restricted here. This is not a browser endpoint - it is the
 * desktop speech engine, which sends no Origin header - and an allow-list that
 * silently rejects the only real client would reproduce the exact failure this
 * relay exists to remove. Authorisation is the speech token, checked by
 * Speechmatics itself upstream: a connection with no valid token gets nothing
 * but a rejection forwarded back.
 */
@Configuration
@EnableWebSocket
public class SttRelayConfig implements WebSocketConfigurer {

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new SttRelayHandler(), "/api/v1/stt/relay")
                .setAllowedOriginPatterns("*");
    }
}
