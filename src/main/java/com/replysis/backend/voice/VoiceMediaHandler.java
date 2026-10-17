package com.replysis.backend.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receives one call's media stream from Twilio and measures it: which tracks arrive and how much speech each holds.
 * This is the question the whole phone feature depends on, "are both people's voices reaching us", answered in numbers.
 * Proof of concept: it keeps counts only, no audio and no words, and refuses a stream that cannot prove which call it is.
 */
public class VoiceMediaHandler extends TextWebSocketHandler {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Map<String, CallAudioStats> calls = new ConcurrentHashMap<>();
    private final Map<String, String> callOf = new ConcurrentHashMap<>();

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (!VoiceProofController.enabled()) { session.close(CloseStatus.POLICY_VIOLATION); return; }
        JsonNode root = JSON.readTree(message.getPayload());
        String event = root.path("event").asText("");
        switch (event) {
            case "start" -> {
                JsonNode p = root.path("start").path("customParameters");
                String callSid = p.path("callSid").asText("");
                String sig = p.path("sig").asText("");
                String expected = VoiceProofController.mediaSignature(callSid);
                boolean ok = !expected.isEmpty()
                        && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8));
                if (!ok) {
                    System.err.println("[VOICE] Media stream refused: it did not prove which call it belongs to");
                    session.close(CloseStatus.POLICY_VIOLATION);
                    return;
                }
                callOf.put(session.getId(), callSid);
                calls.put(session.getId(), new CallAudioStats());
                System.out.println("[VOICE] Media stream started for " + VoiceProofController.redact(callSid)
                        + ", tracks " + root.path("start").path("tracks"));
            }
            case "media" -> {
                CallAudioStats stats = calls.get(session.getId());
                if (stats == null) return;   // media before a valid start is ignored
                JsonNode m = root.path("media");
                stats.add(m.path("track").asText("unknown"), m.path("payload").asText(""));
            }
            case "stop" -> finish(session);
            default -> { /* connected, mark and other events carry nothing we measure */ }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        finish(session);
    }

    private void finish(WebSocketSession session) {
        CallAudioStats stats = calls.remove(session.getId());
        String sid = callOf.remove(session.getId());
        if (stats == null) return;
        boolean both = stats.speechSeconds("inbound") > 1.0 && stats.speechSeconds("outbound") > 1.0;
        System.out.println("[VOICE] Call " + VoiceProofController.redact(sid == null ? "" : sid)
                + " finished. " + stats.summary()
                + " | BOTH SIDES HEARD: " + (both ? "YES" : "NO"));
    }
}
