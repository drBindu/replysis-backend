package com.replysis.backend.controller;

import com.google.cloud.firestore.FieldValue;
import com.google.firebase.cloud.FirestoreClient;
import com.replysis.backend.security.SimpleRateLimiter;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Where the desktop apps say "this broke on somebody's computer".
 *
 * Added 2026-10-01 after a new person's first launch showed "Replysis recovered from an unexpected
 * problem" on a PC nobody could look at. The only record was a log file on that machine. Every new
 * computer was a new unknown, found one at a time by a person photographing a screen.
 *
 * What it takes is deliberately small and carries nothing personal: the app version, the Windows
 * version, the kind of fault, a trimmed message and the method names from the stack. No account, no
 * address, no file paths, no answers, no resume. It needs no sign in, because the interesting faults
 * happen before there is one. It is rate limited, length limited, and written to the server log and to a small Firestore collection (client_errors) for the admin portal's Live panel.
 */
@RestController
@RequestMapping("/api/v1/diagnostics")
public class DiagnosticsController {

    private static final int REPORTS_PER_IP_PER_HOUR = 30;
    private static final int MAX_BODY_BYTES = 8_000;

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern USER_PATH = Pattern.compile("(?i)([A-Z]:\\\\Users\\\\)[^\\\\\\s]+");
    private static final Pattern CONTROL = Pattern.compile("[\\r\\n\\t\\p{Cntrl}]+");

    @Autowired private SimpleRateLimiter rateLimiter;
    @Autowired(required = false) private com.replysis.backend.service.AlertService alerts;

    @PostMapping("/client-error")
    public ResponseEntity<?> clientError(@RequestBody(required = false) Map<String, Object> body,
                                         HttpServletRequest request) {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        String ip = rateLimiter.clientIp(request);
        if (!rateLimiter.tryAcquire("client-error-ip:" + ip, REPORTS_PER_IP_PER_HOUR, 3_600_000L)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        if (body == null) return ResponseEntity.noContent().build();

        String version = clean(body.get("version"), 40);
        String os = clean(body.get("os"), 60);
        String source = clean(body.get("source"), 20);
        String type = clean(body.get("type"), 80);
        String message = clean(body.get("message"), 200);
        String frames = clean(body.get("frames"), 700);

        System.out.println("[CLIENT_ERROR] "
                + "v=" + version
                + " os=" + os
                + " where=" + source
                + " type=" + type
                + " msg=\"" + message + "\""
                + " frames=\"" + frames + "\"");
        record(version, os, source, type, message, frames);
        if (alerts != null) {
            alerts.noteFailure("app-errors", 5, 10 * 60_000L, "Replysis: apps are reporting errors",
                    "Desktop apps are reporting errors on customers' computers.\n\nThe latest: version " + version + ", " + type
                            + (message.isEmpty() ? "" : " (" + message + ")") + ".\n\n"
                            + "Look: https://replysis.com/admin (Live panel, Problems).");
        }
        return ResponseEntity.noContent().build();
    }

    // The same cleaned fields, kept so the admin portal's Live panel can show a problem the moment it happens instead of
    // someone searching a server log afterwards. Never blocks the request and never fails it: a small bounded queue,
    // and a report that cannot be written is simply dropped.
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(
            1, 1, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128),
            runnable -> {
                Thread t = new Thread(runnable, "client-errors");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy());

    private void record(String version, String os, String source, String type, String message, String frames) {
        try {
            writer.execute(() -> {
                try {
                    Map<String, Object> doc = new HashMap<>();
                    doc.put("createdAt", FieldValue.serverTimestamp());
                    doc.put("version", version);
                    doc.put("os", os);
                    doc.put("source", source);
                    doc.put("type", type);
                    doc.put("message", message);
                    doc.put("frames", frames);
                    FirestoreClient.getFirestore().collection("client_errors").add(doc);
                } catch (Exception ignored) {
                    // Not worth a failed request, and nothing to retry: the report is also in the server log.
                }
            });
        } catch (Exception ignored) {
            // Queue closed or full.
        }
    }

    /**
     * One line, bounded, and with anything personal removed, because the text comes from a stranger's
     * computer and goes into our log. Newlines are removed so a report cannot forge a log line.
     */
    static String clean(Object value, int max) {
        if (!(value instanceof String s)) return "";
        s = EMAIL.matcher(s).replaceAll("<email>");
        s = USER_PATH.matcher(s).replaceAll("$1<user>");
        s = CONTROL.matcher(s).replaceAll(" ").replace("\"", "'").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }
}
