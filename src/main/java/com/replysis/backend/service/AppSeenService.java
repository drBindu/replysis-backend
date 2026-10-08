package com.replysis.backend.service;

import com.google.cloud.firestore.FieldValue;
import com.google.firebase.cloud.FirestoreClient;
import com.replysis.backend.security.AppInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Remembers which app (Windows or Mac, and which version) each person last used, so the admin page can say who has which app open.
 *
 * Written onto the person's own document as lastPlatform, lastAppVersion and lastAppSeenAt. Three rules keep it harmless:
 *
 * It never blocks a request: the check is one map lookup, and the write goes to a small bounded queue that drops when full.
 *
 * It writes at most once every ten minutes per person, or sooner only when the platform or version changes, so a busy session
 * costs a handful of writes an hour rather than one per request.
 *
 * It only ever UPDATES an existing document. Creating one here would leave a half-empty account behind (no plan, no credits) for
 * anybody whose first request arrived before their account was set up; a missing document is simply skipped.
 */
@Service
public class AppSeenService {

    private static final long REWRITE_MS = 10 * 60_000L;
    private static final int  REMEMBER_AT_MOST = 20_000;

    private record Seen(String platform, String version, long at) {}

    @Value("${firebase.enabled:false}")
    private boolean firebaseEnabled;

    private final ConcurrentHashMap<String, Seen> seen = new ConcurrentHashMap<>();

    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(
            1, 1, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256),
            runnable -> {
                Thread t = new Thread(runnable, "app-seen");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy());

    /** Notes that this person just used this app. Cheap enough to call on every authenticated request. */
    public void note(String collection, String docId, AppInfo info) {
        if (info == null || docId == null || docId.isBlank() || !firebaseEnabled) return;

        String key = collection + "/" + docId;
        long now = System.currentTimeMillis();
        Seen before = seen.get(key);
        if (before != null
                && before.platform().equals(info.platform())
                && Objects.equals(before.version(), info.version())
                && now - before.at() < REWRITE_MS) {
            return;
        }

        // Recorded first, so a burst of requests from the same person queues one write and not twenty.
        if (seen.size() >= REMEMBER_AT_MOST) seen.clear();
        seen.put(key, new Seen(info.platform(), info.version(), now));

        try {
            writer.execute(() -> write(collection, docId, info));
        } catch (Exception ignored) {
            // The queue is closed or full; the next request tries again after the interval.
        }
    }

    private void write(String collection, String docId, AppInfo info) {
        try {
            Map<String, Object> fields = new HashMap<>();
            fields.put("lastPlatform", info.platform());
            fields.put("lastAppVersion", info.version());
            fields.put("lastAppSeenAt", FieldValue.serverTimestamp());
            FirestoreClient.getFirestore().collection(collection).document(docId)
                    .update(fields).get(8, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // No document yet, or the database was slow. Nothing depends on this label.
        }
    }
}
