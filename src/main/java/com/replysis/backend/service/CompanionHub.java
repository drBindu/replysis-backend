package com.replysis.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The phone's Companion screen: the answers the laptop app is getting right now, shown on the phone of the same account.
 *
 * Added 2026-10-24 for the mobile app. The laptop already streams every answer through /interview/ask, so the answer is
 * copied here as it streams, exactly as the laptop shows it, and sent to that account's open Companion screens. Nothing
 * changes in the desktop apps, so every installed version works with it.
 *
 * Memory only, never written anywhere: the last few turns per account, gone two hours after the last one. Only the same
 * signed-in account can read them, and guests (no account) are never relayed.
 */
@Service
public class CompanionHub {

    private static final int  KEEP_TURNS = 12;
    private static final long KEEP_MS = 2 * 60 * 60_000L;
    private static final int  MAX_SCREENS_PER_ACCOUNT = 4;
    private static final int  MAX_ANSWER_CHARS = 20_000;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong ids = new AtomicLong(System.currentTimeMillis());

    public static final class Turn {
        public final long id;
        public final String question;
        public final String source;
        public final long at;
        final StringBuilder answer = new StringBuilder();
        volatile boolean done;
        Turn(long id, String question, String source) { this.id = id; this.question = question; this.source = source; this.at = System.currentTimeMillis(); }
    }

    private static final class Account {
        final Deque<Turn> turns = new ArrayDeque<>();
        final List<SseEmitter> screens = new CopyOnWriteArrayList<>();
        volatile long lastAt = System.currentTimeMillis();
        // What the laptop app is doing now (2026-10-25): listening or not, and the words it hears, so the phone shows the
        // interviewer as they speak. Kept only as the latest value, never a history.
        volatile boolean listening;
        volatile String heard = "";
        volatile long laptopAt;
        // A request from the phone for the laptop ("stop"), picked up by the laptop's next report. Expires after 30 s.
        volatile String command;
        volatile long commandAt;
    }

    private static final long LAPTOP_FRESH_MS = 90_000;
    private static final long COMMAND_TTL_MS = 30_000;
    private static final int  MAX_HEARD_CHARS = 2_000;

    /**
     * The laptop app reporting what it is doing. Returns how many phone screens are open (so the laptop stops sending
     * when nobody is watching) and any request from the phone, which is handed over once.
     */
    public Map<String, Object> laptop(String uid, boolean listening, String heard) {
        Account a = accounts.computeIfAbsent(uid, k -> new Account());
        String h = heard == null ? "" : (heard.length() > MAX_HEARD_CHARS ? heard.substring(heard.length() - MAX_HEARD_CHARS) : heard);
        boolean changed = a.listening != listening || !a.heard.equals(h) || System.currentTimeMillis() - a.laptopAt > LAPTOP_FRESH_MS / 2;
        a.listening = listening;
        a.heard = h;
        a.laptopAt = System.currentTimeMillis();
        a.lastAt = a.laptopAt;
        if (changed) broadcast(a, Map.of("t", "laptop", "listening", listening, "heard", h));
        String cmd = null;
        synchronized (a) {
            if (a.command != null && System.currentTimeMillis() - a.commandAt < COMMAND_TTL_MS) cmd = a.command;
            a.command = null;
        }
        if (cmd != null) broadcast(a, Map.of("t", "cmd", "cmd", cmd, "state", "delivered"));
        return cmd == null ? Map.of("phones", a.screens.size()) : Map.of("phones", a.screens.size(), "command", cmd);
    }

    /** The phone asking the laptop to do something: "stop" (finish the interview) or "answer" (answer what was just heard). */
    public boolean command(String uid, String cmd) {
        if (!"stop".equals(cmd) && !"answer".equals(cmd)) return false;
        Account a = accounts.get(uid);
        if (a == null || System.currentTimeMillis() - a.laptopAt > LAPTOP_FRESH_MS) return false;   // no laptop to tell
        synchronized (a) { a.command = cmd; a.commandAt = System.currentTimeMillis(); }
        broadcast(a, Map.of("t", "cmd", "cmd", cmd, "state", "sent"));
        return true;
    }

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();

    /** A Companion screen opening: it gets the recent turns at once, then every new word as it arrives. */
    public SseEmitter subscribe(String uid) {
        prune();
        Account a = accounts.computeIfAbsent(uid, k -> new Account());
        while (a.screens.size() >= MAX_SCREENS_PER_ACCOUNT) {
            SseEmitter oldest = a.screens.remove(0);
            try { oldest.complete(); } catch (Exception ignored) { /* already gone */ }
        }
        SseEmitter emitter = new SseEmitter(0L);
        a.screens.add(emitter);
        Runnable drop = () -> a.screens.remove(emitter);
        emitter.onCompletion(drop);
        emitter.onTimeout(drop);
        emitter.onError(e -> drop.run());
        List<Map<String, Object>> recent = new ArrayList<>();
        synchronized (a) {
            for (Turn t : a.turns) recent.add(Map.of("id", t.id, "q", t.question, "a", t.answer.toString(),
                    "done", t.done, "src", t.source, "at", t.at));
        }
        send(a, emitter, Map.of("t", "hist", "turns", recent));
        boolean fresh = System.currentTimeMillis() - a.laptopAt < LAPTOP_FRESH_MS;
        send(a, emitter, Map.of("t", "laptop", "listening", fresh && a.listening, "heard", fresh ? a.heard : "", "seen", fresh));
        return emitter;
    }

    /** Keeps phone connections open through proxies that close quiet ones. Called by the scheduler. */
    public void heartbeat() {
        prune();
        for (Account a : accounts.values())
            for (SseEmitter e : a.screens) send(a, e, Map.of("t", "ping"));
    }

    /**
     * Wraps the stream an answer is written to, so whatever reaches the laptop also reaches the phone. Returns the stream
     * unchanged for a guest or for the phone app itself (its own answers are already on its screen).
     */
    public Relay relay(String uid, String platform, String question, String source, OutputStream out) {
        if (uid == null || "android".equals(platform) || "ios".equals(platform)) return new Relay(null, null, out);
        Account a = accounts.computeIfAbsent(uid, k -> new Account());
        Turn t = new Turn(ids.incrementAndGet(), question == null ? "" : question, source);
        synchronized (a) {
            a.turns.addLast(t);
            while (a.turns.size() > KEEP_TURNS) a.turns.removeFirst();
            a.lastAt = System.currentTimeMillis();
        }
        broadcast(a, Map.of("t", "q", "id", t.id, "q", t.question, "src", source, "at", t.at));
        return new Relay(a, t, out);
    }

    /** The answer stream with the copy attached. finish() marks the turn complete; safe to call more than once. */
    public final class Relay extends OutputStream {
        private final Account account;
        private final Turn turn;
        private final OutputStream out;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        Relay(Account account, Turn turn, OutputStream out) { this.account = account; this.turn = turn; this.out = out; }

        @Override public void write(int b) throws IOException { out.write(b); copy(new byte[] { (byte) b }, 0, 1); }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); copy(b, off, len); }
        @Override public void flush() throws IOException { out.flush(); }

        private void copy(byte[] b, int off, int len) {
            if (turn == null) return;
            for (int i = off; i < off + len; i++) {
                if (b[i] == '\n') { take(line.toString(StandardCharsets.UTF_8)); line.reset(); }
                else if (line.size() < 64_000) line.write(b[i]);
            }
        }

        private void take(String l) {
            if (!l.startsWith("data: ")) return;
            String data = l.substring(6).trim();
            if (data.equals("[DONE]")) { finish(); return; }
            try {
                JsonNode node = mapper.readTree(data).path("choices").path(0).path("delta").path("content");
                if (!node.isTextual() || node.asText().isEmpty()) return;
                String d = node.asText();
                synchronized (account) {
                    if (turn.answer.length() + d.length() > MAX_ANSWER_CHARS) return;
                    turn.answer.append(d);
                }
                broadcast(account, Map.of("t", "a", "id", turn.id, "d", d));
            } catch (Exception ignored) {
                // An error event or a line that is not an answer chunk: nothing to copy.
            }
        }

        public void finish() {
            if (turn == null || turn.done) return;
            turn.done = true;
            broadcast(account, Map.of("t", "end", "id", turn.id));
        }
    }

    private void broadcast(Account a, Map<String, Object> event) {
        for (SseEmitter e : a.screens) send(a, e, event);
    }

    private void send(Account a, SseEmitter e, Map<String, Object> event) {
        try {
            e.send(SseEmitter.event().data(mapper.writeValueAsString(event)));
        } catch (Exception ex) {
            a.screens.remove(e);
            try { e.completeWithError(ex); } catch (Exception ignored) { /* already closed */ }
        }
    }

    private void prune() {
        long cutoff = System.currentTimeMillis() - KEEP_MS;
        accounts.entrySet().removeIf(en -> en.getValue().screens.isEmpty() && en.getValue().lastAt < cutoff);
        for (Account a : accounts.values()) synchronized (a) { a.turns.removeIf(t -> t.at < cutoff); }
    }
}
