package com.replysis.backend.voice;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Where Twilio's media stream connects. Under /api/v1/ so the existing proxy rule already reaches it. */
@Configuration
@EnableWebSocket
public class VoiceProofConfig implements WebSocketConfigurer {

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Twilio sends no Origin header. The call is proven by the signed parameter in its start message instead.
        registry.addHandler(new VoiceMediaHandler(), "/api/v1/voice/media").setAllowedOriginPatterns("*");
    }
}
