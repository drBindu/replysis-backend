package com.replysis.backend.service;

import com.google.cloud.firestore.FieldValue;
import com.google.firebase.cloud.FirestoreClient;
import com.replysis.backend.security.AppInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * One line for the admin Live feed about something the server did for somebody that no billed answer records: a screen read
 * prepared ahead of the question, or an upload it had to turn away. Without this a person could use the app, get a screen read, and
 * leave nothing on the page, because usage events are written only when an answer is charged.
 *
 * Carries no screen content and no words, only what happened, for whom (an id the page turns into an email) and how long it took.
 * Never blocks a request and never fails one: a small bounded queue that drops when full.
 */
@Service
public class ActivityService {

    @Value("${firebase.enabled:false}")
    private boolean firebaseEnabled;

    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(
            1, 1, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256),
            runnable -> {
                Thread t = new Thread(runnable, "activity");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy());

    /**
     * kind: "preread" (a screen read prepared ahead), "preread_failed", or "rejected" (an upload turned away).
     * The app is passed in because this is often called from a worker thread, where the request's headers are no longer to hand.
     */
    public void record(String kind, String identityId, boolean guest, String detail, long ms, AppInfo app) {
        if (!firebaseEnabled || identityId == null || identityId.isBlank()) return;
        Map<String, Object> doc = fields(kind, identityId, guest, detail, ms, app);
        try {
            writer.execute(() -> {
                try {
                    FirestoreClient.getFirestore().collection("activity_events").add(doc);
                } catch (Exception ignored) {
                    // A line in a live feed is not worth a failed request.
                }
            });
        } catch (Exception ignored) {
            // Queue closed or full.
        }
    }

    /** What lands on the document. Separate so a test can see it, and so nothing but these fields can be written. */
    static Map<String, Object> fields(String kind, String identityId, boolean guest, String detail, long ms, AppInfo app) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("createdAt", FieldValue.serverTimestamp());
        doc.put("kind", kind);
        doc.put("identityId", identityId.length() > 128 ? identityId.substring(0, 128) : identityId);
        doc.put("guest", guest);
        doc.put("detail", clean(detail));
        doc.put("ms", Math.max(0, ms));
        if (app != null) {
            doc.put("platform", app.platform());
            doc.put("appVersion", app.version());
        }
        return doc;
    }

    /** One short plain line. */
    static String clean(String text) {
        if (text == null) return "";
        String s = text.replaceAll("[\\r\\n\\t\\p{Cntrl}]+", " ").trim();
        return s.length() > 160 ? s.substring(0, 160) : s;
    }
}
