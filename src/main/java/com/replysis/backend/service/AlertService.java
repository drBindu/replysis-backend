package com.replysis.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.firestore.FieldValue;
import com.google.firebase.cloud.FirestoreClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Tells the owner by email when something customers depend on is broken, so it is found by a message and not by a customer.
 *
 * Three kinds of trouble feed it: a part of the system that stopped answering (ReadinessService), answers that were charged and then
 * could not be delivered (refunds), and desktop apps reporting errors. Each message says what is wrong, what customers feel and
 * where to look. A part that comes back sends one "it is back" message.
 *
 * Rules that keep it useful rather than noisy:
 *
 * One message per problem, then a reminder at most every half hour while it is still broken. At most twelve emails an hour in total,
 * so a bad night cannot bury the inbox; everything beyond that is still logged and kept in alert_events for the admin page.
 *
 * Both server instances run the same checks, so the decision "has this already been sent" is made in the database (a transaction
 * on _alerts/{key}); whichever instance gets there first sends and the other stays quiet. If the database itself is what is down, it
 * falls back to each instance's own memory and the email still goes out, because email does not need the database.
 *
 * It never blocks a request or a check: work goes to a small bounded queue that drops when full. And it never fails anything:
 * with no email key set it logs the alert and records it, and says so.
 *
 * The email goes out through Resend's HTTP API, which needs no extra library and no mail server on this machine. Set
 * ALERTS_RESEND_KEY on the server. The recipient is ALERTS_TO, or ADMIN_EMAIL when that is not set.
 */
@Service
public class AlertService {

    static final long COOLDOWN_MS = 30 * 60_000L;
    static final int  MAX_EMAILS_PER_HOUR = 12;

    @Value("${firebase.enabled:false}") private boolean firebaseEnabled;
    @Value("${alerts.to:${ADMIN_EMAIL:}}") private String to;
    @Value("${alerts.from:Replysis alerts <onboarding@resend.dev>}") private String from;
    @Value("${alerts.resend.key:}") private String resendKey;

    /** How a finished message leaves. Replaced in tests. Returns true when it was handed to the mail service. */
    interface Sender { boolean send(String to, String subject, String body); }

    private Sender sender = this::sendByResend;
    private LongSupplier clock = System::currentTimeMillis;

    private record Open(long openedAt, long lastSentAt) {}

    private final ConcurrentHashMap<String, Open> open = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ArrayDeque<Long>> failures = new ConcurrentHashMap<>();
    private final ArrayDeque<Long> emailedAt = new ArrayDeque<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
    private final ObjectMapper json = new ObjectMapper();

    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(
            1, 1, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64),
            runnable -> {
                Thread t = new Thread(runnable, "alerts");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy());

    /** For tests: a fixed recipient, a capturing sender, a controllable clock, and work run on the calling thread. */
    static AlertService forTest(Sender sender, LongSupplier clock) {
        AlertService s = new AlertService();
        s.sender = sender;
        s.clock = clock;
        s.inline = true;
        s.to = "owner@example.com";
        return s;
    }

    private boolean inline = false;

    // ------------------------------------------------------------------ raising and clearing

    /** Something is broken. Sends once, then reminds at most every half hour while it stays broken. */
    public void raise(String key, String subject, String body) {
        long now = clock.getAsLong();
        Open before = open.get(key);
        if (before != null && now - before.lastSentAt() < COOLDOWN_MS) return;
        open.put(key, new Open(before == null ? now : before.openedAt(), now));
        run(() -> {
            if (!claimRaise(key, now)) return;
            deliver("raised", key, subject, body, now);
        });
    }

    /** The thing that was broken works again. Sends one message, and only if a problem was actually announced. */
    public void recovered(String key, String subject, String body) {
        long now = clock.getAsLong();
        Open was = open.remove(key);
        run(() -> {
            long downMs = claimRecover(key, now, was);
            if (downMs < 0) return;
            long minutes = Math.max(1, downMs / 60_000L);
            deliver("recovered", key, subject, body + "\n\nIt was down for about " + minutes + (minutes == 1 ? " minute." : " minutes."), now);
        });
    }

    /**
     * One more of something that should be rare (a refunded answer, an app error report). Raises an alert when there have been
     * {@code threshold} of them inside {@code windowMs}.
     */
    public void noteFailure(String key, int threshold, long windowMs, String subject, String body) {
        long now = clock.getAsLong();
        ArrayDeque<Long> times = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        int count;
        synchronized (times) {
            times.addLast(now);
            while (!times.isEmpty() && now - times.peekFirst() > windowMs) times.pollFirst();
            count = times.size();
        }
        if (count >= threshold) {
            long minutes = Math.max(1, windowMs / 60_000L);
            raise("spike-" + key, subject, body + "\n\n" + count + " in the last " + minutes + " minutes.");
        }
    }

    /** Sends one message now, to show the owner it works. Ignores the cooldown. Says what happened. */
    public Map<String, Object> sendTest() {
        long now = clock.getAsLong();
        boolean configured = isConfigured();
        boolean emailed = false;
        if (configured && withinHourlyCap(now)) {
            emailed = sender.send(to, "Replysis: test alert",
                    "This is a test. If you can read this, alerts reach you.\n\nSent " + java.time.Instant.ofEpochMilli(now) + ".");
        }
        record(now, "test", "test", "Replysis: test alert", emailed);
        Map<String, Object> out = new HashMap<>();
        out.put("configured", configured);
        out.put("emailed", emailed);
        out.put("to", mask(to));
        return out;
    }

    // ------------------------------------------------------------------ plumbing

    private boolean isConfigured() {
        return resendKey != null && !resendKey.isBlank() && to != null && !to.isBlank();
    }

    private void run(Runnable job) {
        if (inline) { job.run(); return; }
        try { worker.execute(job); } catch (Exception ignored) { /* closed or full: the next check tries again */ }
    }

    private void deliver(String kind, String key, String subject, String body, long now) {
        boolean emailed = false;
        if (!isConfigured()) {
            System.out.println("[ALERT] " + kind + " " + key + ": " + subject + " (not emailed: no email key set on the server)");
        } else if (!withinHourlyCap(now)) {
            System.out.println("[ALERT] " + kind + " " + key + ": " + subject + " (not emailed: already sent " + MAX_EMAILS_PER_HOUR + " this hour)");
        } else {
            emailed = sender.send(to, subject, body);
            System.out.println("[ALERT] " + kind + " " + key + ": " + subject + (emailed ? " (emailed)" : " (email failed)"));
        }
        record(now, kind, key, subject, emailed);
    }

    private synchronized boolean withinHourlyCap(long now) {
        while (!emailedAt.isEmpty() && now - emailedAt.peekFirst() > 3_600_000L) emailedAt.pollFirst();
        if (emailedAt.size() >= MAX_EMAILS_PER_HOUR) return false;
        emailedAt.addLast(now);
        return true;
    }

    /** Whether this instance should send. The database settles it so two instances do not both email. */
    private boolean claimRaise(String key, long now) {
        if (!firebaseEnabled) return true;
        try {
            var db = FirestoreClient.getFirestore();
            var ref = db.collection("_alerts").document(key);
            return db.runTransaction(tx -> {
                var snap = tx.get(ref).get();
                boolean isOpen = snap.exists() && Boolean.TRUE.equals(snap.getBoolean("open"));
                Long last = isOpen ? snap.getLong("lastSentAt") : null;
                if (last != null && now - last < COOLDOWN_MS) return false;
                Long openedAt = isOpen ? snap.getLong("openedAt") : null;
                Map<String, Object> state = new HashMap<>();
                state.put("open", true);
                state.put("openedAt", openedAt != null ? openedAt : now);
                state.put("lastSentAt", now);
                tx.set(ref, state);
                return true;
            }).get(8, TimeUnit.SECONDS);
        } catch (Exception e) {
            return true; // the database is unreachable (possibly the very problem): send anyway
        }
    }

    /** How long it was down in milliseconds when this instance should announce the recovery, or -1 to stay quiet. */
    private long claimRecover(String key, long now, Open was) {
        long fallback = was == null ? -1 : now - was.openedAt();
        if (!firebaseEnabled) return fallback;
        try {
            var db = FirestoreClient.getFirestore();
            var ref = db.collection("_alerts").document(key);
            return db.runTransaction(tx -> {
                var snap = tx.get(ref).get();
                if (!snap.exists() || !Boolean.TRUE.equals(snap.getBoolean("open"))) return -1L;
                Long openedAt = snap.getLong("openedAt");
                Map<String, Object> state = new HashMap<>();
                state.put("open", false);
                state.put("closedAt", now);
                tx.set(ref, state, com.google.cloud.firestore.SetOptions.merge());
                return openedAt == null ? 0L : now - openedAt;
            }).get(8, TimeUnit.SECONDS);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** A line in alert_events, so the admin page can show what was raised and whether an email went out. */
    private void record(long now, String kind, String key, String subject, boolean emailed) {
        if (!firebaseEnabled) return;
        try {
            Map<String, Object> doc = new HashMap<>();
            doc.put("createdAt", FieldValue.serverTimestamp());
            doc.put("kind", kind);
            doc.put("key", key);
            doc.put("subject", subject);
            doc.put("emailed", emailed);
            FirestoreClient.getFirestore().collection("alert_events").add(doc);
        } catch (Exception ignored) {
            // History only. The alert itself has already gone out.
        }
    }

    private boolean sendByResend(String recipient, String subject, String body) {
        try {
            String payload = json.writeValueAsString(Map.of(
                    "from", from, "to", List.of(recipient), "subject", subject, "text", body));
            var request = HttpRequest.newBuilder(URI.create("https://api.resend.com/emails"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + resendKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            var reply = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (reply.statusCode() / 100 == 2) return true;
            String said = reply.body() == null ? "" : reply.body();
            System.err.println("[ALERT] the mail service answered " + reply.statusCode() + ": " + (said.length() > 200 ? said.substring(0, 200) : said));
            return false;
        } catch (Exception e) {
            System.err.println("[ALERT] email failed: " + e.getMessage());
            return false;
        }
    }

    /** "kr...@gmail.com": enough to see where it went, not enough to harvest. */
    static String mask(String email) {
        if (email == null || !email.contains("@")) return "";
        int at = email.indexOf('@');
        String name = email.substring(0, at);
        return (name.length() <= 2 ? name.charAt(0) + "" : name.substring(0, 2)) + "..." + email.substring(at);
    }
}
