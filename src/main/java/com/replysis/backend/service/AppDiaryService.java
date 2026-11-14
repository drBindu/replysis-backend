package com.replysis.backend.service;

import com.google.cloud.firestore.FieldValue;
import com.google.firebase.cloud.FirestoreClient;
import com.replysis.backend.security.AppInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The app activity diary (owner, 2026-11-14: "only to see problems or issues users face and fix them; we never take users' data
 * or passwords"). The Mac and Windows apps send what the person did in the app (opened it, started listening, changed a setting,
 * saw a problem) in batches; one batch becomes one document in app_activity, so a busy app costs one write every ten seconds.
 *
 * It records names and numbers only. Every event name and prop key must be a short lowercase word, and every prop value a number,
 * a true/false or a short token with no spaces. Anything else (a sentence, a transcript, screen text, a resume line) is dropped
 * here, whatever the app sent, so a bug in an app cannot turn the diary into a record of what somebody said or saw.
 *
 * Never blocks and never fails the request: the write goes to a small bounded queue that drops when full.
 */
@Service
public class AppDiaryService {

    public static final String COLLECTION = "app_activity";
    public static final int MAX_EVENTS = 200;
    static final int MAX_PROPS = 12;
    /** Batches older than this are kept by nobody: expireAt lets a Firestore TTL policy clear them. */
    static final long KEEP_MS = 90L * 24 * 60 * 60_000L;

    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{0,39}$");
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_.:+\\-]{0,40}$");
    private static final Pattern SESSION = Pattern.compile("^[A-Za-z0-9_\\-]{1,64}$");

    @Value("${firebase.enabled:false}")
    private boolean firebaseEnabled;

    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(
            1, 1, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256),
            runnable -> {
                Thread t = new Thread(runnable, "app-diary");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy());

    /** Stores one batch for this person. Returns how many events were kept after cleaning. */
    public int store(String uid, AppInfo app, List<?> rawEvents) {
        List<Map<String, Object>> events = clean(rawEvents);
        if (events.isEmpty() || !firebaseEnabled || uid == null || uid.isBlank()) return events.size();
        Map<String, Object> doc = batchDoc(uid, app, events, System.currentTimeMillis());
        try {
            writer.execute(() -> {
                try {
                    FirestoreClient.getFirestore().collection(COLLECTION).add(doc).get(8, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // A diary line is not worth a failed request.
                }
            });
        } catch (Exception ignored) {
            // Queue closed or full.
        }
        return events.size();
    }

    /**
     * Deletes batches past expireAt, so the Trust Center's "deleted after 90 days" holds without a console TTL setting.
     * Hourly, at most 2,000 a run; running on the standby server too is harmless.
     */
    @org.springframework.scheduling.annotation.Scheduled(initialDelay = 5 * 60_000, fixedDelay = 60 * 60_000)
    public void deleteExpired() {
        if (!firebaseEnabled) return;
        try {
            var db = FirestoreClient.getFirestore();
            var old = db.collection(COLLECTION).whereLessThan("expireAt", new Date()).limit(400);
            for (int round = 0; round < 5; round++) {
                var snap = old.get().get(30, TimeUnit.SECONDS);
                if (snap.isEmpty()) return;
                var batch = db.batch();
                snap.getDocuments().forEach(d -> batch.delete(d.getReference()));
                batch.commit().get(30, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            System.out.println("[DIARY] cleanup skipped: " + e.getClass().getSimpleName());
        }
    }

    /** The document one batch becomes. The person comes from the sign-in token, never from the body. */
    static Map<String, Object> batchDoc(String uid, AppInfo app, List<Map<String, Object>> events, long now) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("uid", uid);
        doc.put("platform", app == null ? "unknown" : app.platform());
        doc.put("appVersion", app == null ? "" : app.version());
        doc.put("receivedAt", FieldValue.serverTimestamp());
        doc.put("expireAt", new Date(now + KEEP_MS));
        doc.put("count", events.size());
        long first = Long.MAX_VALUE, last = 0;
        String session = "";
        for (Map<String, Object> e : events) {
            long t = (Long) e.get("t");
            first = Math.min(first, t);
            last = Math.max(last, t);
            if (session.isEmpty() && e.get("session_id") instanceof String s) session = s;
        }
        doc.put("firstT", first);
        doc.put("lastT", last);
        doc.put("sessionId", session);
        // session_id is the same for the whole batch in practice; kept on the batch, not repeated per event.
        List<Map<String, Object>> slim = new ArrayList<>();
        for (Map<String, Object> e : events) {
            Map<String, Object> s = new LinkedHashMap<>(e);
            s.remove("session_id");
            slim.add(s);
        }
        doc.put("events", slim);
        return doc;
    }

    /** Keeps only well-formed events with safe props, in order, at most MAX_EVENTS. */
    static List<Map<String, Object>> clean(List<?> raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw == null) return out;
        long now = System.currentTimeMillis();
        for (Object o : raw) {
            if (out.size() >= MAX_EVENTS) break;
            if (!(o instanceof Map<?, ?> m)) continue;
            Object name = m.get("event");
            if (!(name instanceof String event) || !NAME.matcher(event).matches()) continue;
            long t = m.get("t") instanceof Number n ? n.longValue() : now;
            // A clock far off (or a made-up time) is replaced by the time it arrived, so the feed stays in order.
            if (t < now - 30L * 24 * 60 * 60_000L || t > now + 10 * 60_000L) t = now;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("t", t);
            e.put("event", event);
            if (m.get("session_id") instanceof String s && SESSION.matcher(s).matches()) e.put("session_id", s);
            e.put("props", cleanProps(m.get("props")));
            out.add(e);
        }
        return out;
    }

    static Map<String, Object> cleanProps(Object raw) {
        Map<String, Object> props = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> m)) return props;
        for (Map.Entry<?, ?> entry : m.entrySet()) {
            if (props.size() >= MAX_PROPS) break;
            if (!(entry.getKey() instanceof String key) || !NAME.matcher(key).matches()) continue;
            Object v = entry.getValue();
            if (v instanceof Boolean b) props.put(key, b);
            // Not a ?: here: it would promote every whole number to a double.
            else if (v instanceof Double || v instanceof Float) props.put(key, ((Number) v).doubleValue());
            else if (v instanceof Number n) props.put(key, n.longValue());
            else if (v instanceof String s && TOKEN.matcher(s).matches()) props.put(key, s);
            // Anything else (text with spaces, lists, objects, long strings) is dropped.
        }
        return props;
    }
}
