package com.replysis.backend.service;

import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.cloud.FirestoreClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * One row per billable action, so the admin portal can answer who used what.
 *
 * Before this existed nothing recorded a charge. deductCredits moved two
 * numbers on the user document, credits down and creditsUsed up, and printed a
 * line to stdout. That is enough to bill someone and not enough to tell them
 * why: the portal could show a total and nothing behind it, no per-user
 * history, no split between a spoken answer and a screen read, no idea which
 * provider served it. Container logs are not an answer either, since they are
 * rotated and gone.
 *
 * Two things this deliberately does NOT do.
 *
 * It never blocks the request. Answers are the product and first-token latency
 * is the number that matters, so a write goes onto a small bounded queue and
 * the caller returns immediately. If the queue is full the event is dropped and
 * counted, because losing a usage row is an acceptable price and adding
 * milliseconds to an answer is not.
 *
 * It never fails a request. Every path here swallows its own exceptions. A
 * charge that succeeded must not be reported as failed because an analytics
 * write could not be made.
 */
@Service
public class UsageEventService {

    public static final String COLLECTION = "usage_events";

    /** What was bought. Kept short: these are grouped in the admin portal. */
    public static final String ACTION_ANSWER = "answer";
    public static final String ACTION_SCREEN = "screen";
    public static final String ACTION_RESUME_ANALYSIS = "resume_analysis";
    public static final String ACTION_RESUME_TAILOR = "resume_tailor";

    /**
     * Context a charge happened in. The credits service knows the price but not
     * the purchase, so callers pass this in.
     *
     * provider and model may be null at the moment of charging: the charge is
     * taken up front, before a provider has accepted, so an event written then
     * records the provider that was going to be tried.
     */
    public record Context(String action, String provider, String model, String eventId) {
        public Context(String action, String provider, String model) {
            this(action, provider, model, null);
        }
        public static Context of(String action) { return new Context(action, null, null, null); }

        /** A context carrying a fresh id, so tokens can be attached to it later. */
        public static Context tracked(String action, String provider, String model) {
            return new Context(action, provider, model, java.util.UUID.randomUUID().toString());
        }
    }

    // One thread, small queue. Firestore writes are ordered per document and
    // these are independent documents, so a single worker is enough for the
    // volume a single-instance backend can generate, and it keeps the memory
    // ceiling obvious.
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(
            1, 1, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(512),
            runnable -> {
                Thread t = new Thread(runnable, "usage-events");
                t.setDaemon(true);   // must never hold up a shutdown
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy());

    private volatile long dropped;

    /**
     * Record a charge, or a refund when credits is negative.
     *
     * @param identityId uid for a signed-in user, device id for a guest
     * @param email      may be null; the portal falls back to the id
     * @param guest      true when identityId is a device rather than a uid
     * @param credits    positive for a charge, negative for a refund
     */
    public void record(String identityId, String email, boolean guest,
                       int credits, Context context) {
        if (identityId == null || identityId.isBlank()) return;

        // Snapshot every value now. The caller's objects may be mutated or
        // reused after this returns, and the write happens later on the worker.
        final String action   = context == null ? "unknown" : nullToUnknown(context.action());
        final String provider = context == null ? null : context.provider();
        final String model    = context == null ? null : context.model();
        final String eventId  = context == null || context.eventId() == null
                ? java.util.UUID.randomUUID().toString() : context.eventId();
        final Instant now     = Instant.now();

        int queued = writer.getQueue().size();
        if (queued >= 512) { dropped++; return; }

        try {
            writer.execute(() -> {
                try {
                    Map<String, Object> row = new HashMap<>();
                    row.put("identityId", identityId);
                    row.put("email", email == null || email.isBlank() ? null : email.toLowerCase());
                    row.put("guest", guest);
                    row.put("action", action);
                    row.put("provider", provider);
                    row.put("model", model);
                    row.put("credits", credits);
                    row.put("refund", credits < 0);
                    // Server timestamp, not the Instant captured above. The
                    // Firestore Java client has no mapping for java.time.Instant
                    // and writing one throws at serialization time, which on this
                    // path would be swallowed and the row silently lost. The
                    // server clock is also the right clock: these rows are read
                    // back as a time range and a container with a drifting clock
                    // would file its usage under the wrong day.
                    row.put("createdAt", FieldValue.serverTimestamp());
                    // Kept alongside it so the portal can group by day without
                    // reading every document back out.
                    row.put("day", now.toString().substring(0, 10));

                    Firestore db = FirestoreClient.getFirestore();
                    // Named rather than auto-id, so attachTokens below can find
                    // this row once the provider has finished streaming and
                    // finally says how many tokens it used.
                    db.collection(COLLECTION).document(eventId).set(row);
                } catch (Exception e) {
                    // Deliberately swallowed. See the class comment: an
                    // analytics write must never surface as a failed charge.
                    System.err.println("[usage] write failed: " + e.getClass().getSimpleName());
                }
            });
        } catch (Exception ignored) {
            dropped++;
        }
    }

    /**
     * Attach what the provider actually billed, once the stream has ended.
     *
     * Token counts only arrive in the last chunk, long after the charge was
     * taken, so this is a second write against the row the charge created. It
     * is what turns "this user spent 40 credits" into "this user cost us this
     * many tokens", which is the only way to compare what a customer pays with
     * what they cost.
     *
     * An update rather than a new row, so one action stays one row. If the row
     * is not there, because the queue dropped it or the write is still in
     * flight behind this one, the update is skipped: the single worker keeps
     * these in order, so in practice the charge row is always written first.
     */
    public void attachTokens(String eventId, long promptTokens, long completionTokens, long latencyMs) {
        if (eventId == null || eventId.isBlank()) return;
        if (promptTokens <= 0 && completionTokens <= 0) return;

        try {
            writer.execute(() -> {
                try {
                    Map<String, Object> patch = new HashMap<>();
                    patch.put("promptTokens", promptTokens);
                    patch.put("completionTokens", completionTokens);
                    patch.put("totalTokens", promptTokens + completionTokens);
                    if (latencyMs > 0) patch.put("latencyMs", latencyMs);

                    Firestore db = FirestoreClient.getFirestore();
                    db.collection(COLLECTION).document(eventId).update(patch);
                } catch (Exception e) {
                    // Not found is normal when the charge row was dropped. Any
                    // other failure is still not worth surfacing to a caller
                    // who has already delivered an answer.
                    System.err.println("[usage] token attach skipped: " + e.getClass().getSimpleName());
                }
            });
        } catch (Exception ignored) {
            dropped++;
        }
    }

    /** Events discarded because the queue was full. Exposed on the health endpoint. */
    public long droppedCount() { return dropped; }

    private static String nullToUnknown(String s) {
        return s == null || s.isBlank() ? "unknown" : s;
    }
}
