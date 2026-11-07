package com.replysis.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;

/**
 * Emails the owner when a day's speech transcription goes well past normal (round 30, 2026-11-07).
 *
 * Listening time is metered by what the apps report, and a speech token lasts an hour and can open any number of
 * connections, so a modified client could stream audio for hours without reporting a minute. Shortening the token would
 * make every reconnect wait for a fresh one, which costs answer speed, so this watches the bill instead: once an hour it
 * reads today's Deepgram hours and sends one notice per day if they pass the limit. Normal is under four hours a day
 * (7.9 hours over the 14 days to 2026-11-07).
 */
@Service
public class SpeechSpendWatch {

    @Value("${firebase.enabled:false}") private boolean firebaseEnabled;
    @Value("${deepgram.api.key:}") private String deepgramKey;
    @Value("${speech.spend.alert.hours:12}") private double limitHours;
    @Autowired private AlertService alerts;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** Total hours in a Deepgram usage reply, or -1 when the reply cannot be read. */
    static double hoursIn(JsonNode usage) {
        if (usage == null || !usage.path("results").isArray()) return -1;
        double hours = 0;
        for (JsonNode day : usage.path("results")) hours += day.path("hours").asDouble(0);
        return hours;
    }

    /** One notice per day, whichever instance sees it first, and only past the limit. */
    void judge(String day, double hours, double limit) {
        if (hours < 0 || hours <= limit) return;
        String spoken = String.format(Locale.ROOT, "%.1f", hours);
        alerts.notice("speech-spend-" + day,
                "Replysis speech use is high today: " + spoken + " hours",
                "Deepgram has transcribed " + spoken + " hours of audio today (UTC), past the " + (int) limit
                        + " hour alert line. A normal day is under four hours.\n\n"
                        + "This can be real growth, or someone streaming with a Replysis speech token without the app. "
                        + "Compare it with listening in the admin panel: https://replysis.com/admin\n\n"
                        + "Deepgram usage: https://console.deepgram.com");
    }

    @Scheduled(initialDelay = 300_000, fixedDelay = 3_600_000)
    public void look() {
        if (!firebaseEnabled || deepgramKey == null || deepgramKey.isBlank()) return;
        try {
            String today = LocalDate.now(ZoneOffset.UTC).toString();
            JsonNode projects = get("https://api.deepgram.com/v1/projects");
            String project = projects == null ? null : projects.path("projects").path(0).path("project_id").asText(null);
            if (project == null) return;
            JsonNode usage = get("https://api.deepgram.com/v1/projects/" + project + "/usage?start=" + today + "&end=" + today);
            judge(today, hoursIn(usage), limitHours);
        } catch (Exception e) {
            System.err.println("[SPEECH_SPEND] look failed: " + e.getClass().getSimpleName());
        }
    }

    private JsonNode get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                .header("Authorization", "Token " + deepgramKey)
                .timeout(Duration.ofSeconds(15)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            System.err.println("[SPEECH_SPEND] Deepgram usage HTTP " + response.statusCode());
            return null;
        }
        return mapper.readTree(response.body());
    }

    /** Tests only. */
    static SpeechSpendWatch forTest(AlertService alerts) {
        SpeechSpendWatch w = new SpeechSpendWatch();
        w.alerts = alerts;
        return w;
    }
}
