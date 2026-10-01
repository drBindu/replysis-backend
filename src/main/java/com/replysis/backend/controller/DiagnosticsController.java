package com.replysis.backend.controller;

import com.replysis.backend.security.SimpleRateLimiter;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
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
 * happen before there is one. It is rate limited, length limited, and written to the server log only.
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

        System.out.println("[CLIENT_ERROR] "
                + "v=" + clean(body.get("version"), 40)
                + " os=" + clean(body.get("os"), 60)
                + " where=" + clean(body.get("source"), 20)
                + " type=" + clean(body.get("type"), 80)
                + " msg=\"" + clean(body.get("message"), 200) + "\""
                + " frames=\"" + clean(body.get("frames"), 700) + "\"");
        return ResponseEntity.noContent().build();
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
