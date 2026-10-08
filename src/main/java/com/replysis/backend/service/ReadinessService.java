package com.replysis.backend.service;

import com.google.firebase.cloud.FirestoreClient;
import com.replysis.backend.controller.InterviewController;
import com.replysis.backend.controller.SttController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Keeps everything an answer depends on warm, and records how each part answered, so "is it awake?" has a real answer.
 *
 * A round runs every five seconds (readiness.round.ms). Each round checks the database and the website. Every sixth round (half a
 * minute) it also makes one tiny request to each model, and every twelfth (a minute) one to each speech provider. The owner asked
 * for five seconds across the board; the two models and the speech tokens are deliberately not asked that often by default, because
 * their providers meter per request and twelve a minute to each would use most of a free tier's allowance and could rate-limit
 * real customers. readiness.models.every=1 (READINESS_MODELS_EVERY=1) turns it up if the plans allow it.
 *
 * That does two jobs. It holds the connections open, so the first question after a quiet hour is as fast as
 * the hundredth. And it measures, so the admin page can show each part as awake, slow or down, with how long it took, instead of
 * finding out from a customer.
 *
 * What it costs: a few tokens per request on the two models (under a cent a day), a small database check every five seconds (about seventeen thousand a day), and free
 * reachability checks on the speech providers. Speech itself is billed by audio, not by an open connection, so it needs nothing.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "readiness.enabled", havingValue = "true", matchIfMissing = true)
public class ReadinessService {

    /** One part's latest result. ms is how long the check took; ok false means it failed or timed out. */
    public record Probe(boolean ok, long ms, String note, long at) {}

    // Anything slower than this still works but is called out, because it is what a customer would feel.
    private static final long SLOW_MS = 2_500;
    private static final long PROBE_TIMEOUT_MS = 9_000;

    @Autowired private InterviewController interview;
    @Autowired private SttController stt;

    @Value("${firebase.enabled:false}") private boolean firebaseEnabled;
    /** The two models are asked on every Nth round (6 x 5 s = 30 s). 1 means every round. */
    @Value("${readiness.models.every:6}") private int modelsEvery;
    /** The speech providers are asked on every Nth round (12 x 5 s = 60 s). */
    @Value("${readiness.speech.every:12}") private int speechEvery;
    @Value("${readiness.website.url:https://replysis.com/}") private String websiteUrl;

    private final long startedAt = System.currentTimeMillis();
    private final Map<String, Probe> latest = new ConcurrentHashMap<>();
    private volatile long lastRoundAt = 0;
    private int round = 0;
    private final ExecutorService pool = Executors.newFixedThreadPool(5, r -> {
        Thread t = new Thread(r, "readiness");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();

    @Scheduled(initialDelay = 4_000, fixedDelayString = "${readiness.round.ms:5000}")
    public void keepWarm() {
        var jobs = new LinkedHashMap<String, CompletableFuture<Probe>>();
        int n = round++;
        if (n % Math.max(1, modelsEvery) == 0) {
            jobs.put("answer_model", run(() -> fromMillis(interview.warmAnswerModel())));
            jobs.put("screen_model", run(() -> fromMillis(interview.warmScreenModel())));
        }
        jobs.put("database", run(this::database));
        jobs.put("website", run(() -> reach(websiteUrl)));
        // Speech asks the providers for a real (free, throwaway) token, which is exactly what starting to listen does, so a revoked or
        // exhausted key shows up here before a customer finds it. Once a minute is plenty to keep those connections open.
        if (n % Math.max(1, speechEvery) == 0) {
            jobs.put("speech", run(() -> fromMillis(stt.warmDeepgram())));
            jobs.put("speech_backup", run(() -> fromMillis(stt.warmSpeechmatics())));
        }

        jobs.forEach((name, job) -> {
            Probe result;
            try {
                result = job.get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                result = new Probe(false, PROBE_TIMEOUT_MS, "no answer in " + PROBE_TIMEOUT_MS / 1000 + " s", System.currentTimeMillis());
            }

            Probe before = latest.put(name, result);
            // Say it once when a part changes state, not on every round.
            if (before == null || before.ok() != result.ok()) {
                System.out.println("[READY] " + name + (result.ok() ? " is awake (" + result.ms() + "ms)" : " is DOWN: " + result.note()));
            }
        });
        lastRoundAt = System.currentTimeMillis();
    }

    /** What the status endpoint returns: the latest result of each part, how old it is, and how long this instance has been up. */
    public Map<String, Object> snapshot() {
        long now = System.currentTimeMillis();
        var systems = new LinkedHashMap<String, Object>();
        for (String name : new String[]{"answer_model", "screen_model", "database", "speech", "speech_backup", "website"}) {
            Probe p = latest.get(name);
            if (p == null) {
                systems.put(name, Map.of("state", "starting"));
                continue;
            }
            String state = !p.ok() ? "down" : p.ms() >= SLOW_MS ? "slow" : "awake";
            systems.put(name, Map.of("state", state, "ms", p.ms(), "note", p.note() == null ? "" : p.note(), "ageMs", now - p.at()));
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("now", now);
        out.put("lastRoundAt", lastRoundAt);
        out.put("roundAgeMs", lastRoundAt == 0 ? -1 : now - lastRoundAt);
        out.put("uptimeSeconds", (now - startedAt) / 1000);
        out.put("systems", systems);
        return out;
    }

    private CompletableFuture<Probe> run(java.util.function.Supplier<Probe> work) {
        return CompletableFuture.supplyAsync(work, pool);
    }

    private static Probe fromMillis(long ms) {
        long at = System.currentTimeMillis();
        if (ms == -2) return new Probe(true, 0, "not set up here", at);
        if (ms < 0)   return new Probe(false, 0, "the request failed", at);
        return new Probe(true, ms, "", at);
    }

    private Probe database() {
        long at = System.currentTimeMillis();
        if (!firebaseEnabled) return new Probe(true, 0, "not set up here", at);
        try {
            // A transaction, not a plain read: charging for an answer is a transaction, and that is the path that was 2.6 s when cold.
            var db = FirestoreClient.getFirestore();
            var ref = db.collection("_warmup").document("ping");
            db.runTransaction(transaction -> transaction.get(ref).get()).get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return new Probe(true, System.currentTimeMillis() - at, "", at);
        } catch (Exception e) {
            return new Probe(false, System.currentTimeMillis() - at, "the database did not answer", at);
        }
    }

    /** The public website, through the same front door a visitor uses. Any answer short of a server error means it is up. */
    private Probe reach(String url) {
        long at = System.currentTimeMillis();
        try {
            var request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofMillis(PROBE_TIMEOUT_MS)).GET().build();
            var reply = http.send(request, HttpResponse.BodyHandlers.discarding());
            long ms = System.currentTimeMillis() - at;
            return reply.statusCode() < 500
                    ? new Probe(true, ms, "", at)
                    : new Probe(false, ms, "the website answered " + reply.statusCode(), at);
        } catch (Exception e) {
            return new Probe(false, System.currentTimeMillis() - at, "could not reach the website", at);
        }
    }
}
