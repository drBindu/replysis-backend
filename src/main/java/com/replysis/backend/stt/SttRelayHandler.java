package com.replysis.backend.stt;

import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Carries the speech connection on our own domain.
 *
 * WHY THIS EXISTS
 *
 * The engine connected straight to wss://eu.rt.speechmatics.com. That works on
 * a home network and fails on the networks our users are actually on. Tried on
 * a shop-floor display machine, everything worked - sign in, credits, the
 * window - and the microphone never connected, because the network allowed
 * https://replysis.com and did not allow a websocket to a domain it had never
 * heard of.
 *
 * It is tempting to call that the network's fault. It is not a useful thing to
 * call it. A candidate on a work laptop, on campus wifi, or in a hotel has a
 * network that permits ordinary browsing and blocks unknown domains, and they
 * will not debug it - they will decide the product does not work. The choice to
 * depend on a third-party domain was ours, so the fix is ours.
 *
 * This relay is a transparent pipe. It does not parse or alter the protocol: it
 * forwards the client's frames to Speechmatics and Speechmatics' frames back,
 * in order, in both directions. The engine speaks exactly the protocol it spoke
 * before; only the address changes. Anything cleverer than a pipe would be a
 * second implementation of somebody else's protocol, which would then need to
 * track their changes.
 *
 * WHAT IT DOES NOT FIX
 *
 * A network that blocks websockets outright, regardless of destination, still
 * blocks this. That case is rarer - a websocket upgrade to a host already
 * reachable over https usually passes - but it is not nothing, and the client
 * keeps its direct connection as a fallback so a relay failure is not worse
 * than no relay.
 */
public class SttRelayHandler extends AbstractWebSocketHandler {

    /**
     * The upstream this forwards to. Region matters: self-service keys are
     * locked to the account's signup region, and the wrong region rejects a
     * good key with the same error a bad key gets.
     */
    private static final String UPSTREAM = "wss://eu.rt.speechmatics.com/v2";

    /** Live upstream connections, keyed by the browser-side session id. */
    private final Map<String, WebSocket> upstreams = new ConcurrentHashMap<>();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // The token the engine would have sent to Speechmatics itself. Passed
        // straight through: this relay deliberately does not mint or hold
        // credentials, so it cannot become a second place where auth logic
        // has to be kept correct.
        String token = tokenFrom(session);
        if (token == null || token.isBlank()) {
            close(session, CloseStatus.POLICY_VIOLATION.withReason("no speech token"));
            return;
        }

        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket ws) {
                ws.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                send(session, new TextMessage(data.toString(), last));
                ws.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
                send(session, new BinaryMessage(data, last));
                ws.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
                // Speechmatics hanging up must hang up on the client too. A
                // half-open pipe is the shape that produces an app which looks
                // connected and transcribes nothing.
                close(session, new CloseStatus(normalise(code), reason));
                return null;
            }

            @Override
            public void onError(WebSocket ws, Throwable error) {
                System.err.println("[STT-RELAY] upstream error: " + error);
                close(session, CloseStatus.SERVER_ERROR.withReason("upstream error"));
            }
        };

        try {
            WebSocket upstream = http.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + token)
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(UPSTREAM), listener)
                    .join();
            upstreams.put(session.getId(), upstream);
            System.out.println("[STT-RELAY] open " + session.getId());
        } catch (Exception e) {
            System.err.println("[STT-RELAY] could not reach upstream: " + e);
            close(session, CloseStatus.SERVICE_OVERLOAD.withReason("upstream unavailable"));
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        WebSocket up = upstreams.get(session.getId());
        if (up != null) up.sendBinary(message.getPayload(), message.isLast());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        WebSocket up = upstreams.get(session.getId());
        if (up != null) up.sendText(message.getPayload(), message.isLast());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        WebSocket up = upstreams.remove(session.getId());
        if (up != null) up.sendClose(WebSocket.NORMAL_CLOSURE, "client closed");
        System.out.println("[STT-RELAY] closed " + session.getId() + " " + status);
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    /**
     * The speech token, from the query string. It is not in a header because
     * the browser WebSocket API cannot set one, and a client that cannot be
     * changed later is a worse constraint than a token in a query string on a
     * TLS connection to our own host.
     */
    private static String tokenFrom(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null || uri.getQuery() == null) return null;
        for (String pair : uri.getQuery().split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals("jwt"))
                return java.net.URLDecoder.decode(pair.substring(eq + 1),
                        java.nio.charset.StandardCharsets.UTF_8);
        }
        return null;
    }

    /** Sends without letting one dead socket take the process with it. */
    private static void send(WebSocketSession session, WebSocketMessage<?> message) {
        try {
            if (session.isOpen()) {
                synchronized (session) { session.sendMessage(message); }
            }
        } catch (Exception e) {
            System.err.println("[STT-RELAY] send failed: " + e.getMessage());
        }
    }

    private static void close(WebSocketSession session, CloseStatus status) {
        try { if (session.isOpen()) session.close(status); } catch (Exception ignored) { }
    }

    /**
     * Close codes 1005 and 1006 are "no status" and "abnormal" - they are
     * things a socket observes, not things it is allowed to send, and passing
     * one on throws.
     */
    private static int normalise(int code) {
        return (code == 1005 || code == 1006 || code < 1000) ? 1011 : code;
    }
}
