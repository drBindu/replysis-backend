package com.replysis.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** A day of speech well past normal emails the owner once; a normal day, or an unreadable reply, never does. */
class SpeechSpendWatchTests {

    private final List<String> sent = new ArrayList<>();
    private final AtomicLong now = new AtomicLong(1_000_000_000L);

    private SpeechSpendWatch watch() {
        AlertService s = AlertService.forTest((to, subject, body) -> { sent.add(subject + "\n" + body); return true; }, now::get);
        try {
            var f = AlertService.class.getDeclaredField("resendKey");
            f.setAccessible(true);
            f.set(s, "test-key");
        } catch (Exception e) { throw new AssertionError(e); }
        return SpeechSpendWatch.forTest(s);
    }

    @Test
    void readsTheHoursOfADeepgramUsageReply() throws Exception {
        var json = new ObjectMapper().readTree("{\"results\":[{\"start\":\"2026-11-07\",\"hours\":1.5,\"requests\":3},{\"hours\":2.25}]}");
        assertEquals(3.75, SpeechSpendWatch.hoursIn(json), 1e-9);
        assertEquals(-1, SpeechSpendWatch.hoursIn(new ObjectMapper().readTree("{\"err\":\"x\"}")));
        assertEquals(-1, SpeechSpendWatch.hoursIn(null));
    }

    @Test
    void aHighDayEmailsOnceAndANormalDayNever() {
        SpeechSpendWatch w = watch();
        w.judge("2026-11-07", 3.2, 12);
        assertTrue(sent.isEmpty(), "a normal day sends nothing");
        w.judge("2026-11-07", -1, 12);
        assertTrue(sent.isEmpty(), "an unreadable reply sends nothing");
        for (int hour = 0; hour < 5; hour++) w.judge("2026-11-07", 13 + hour, 12);
        assertEquals(1, sent.size(), "one notice per day, not one per hourly look");
        w.judge("2026-11-08", 20, 12);
        assertEquals(2, sent.size(), "a new day can warn again");
    }

    @Test
    void theNoticeFollowsTheCopyRules() {
        SpeechSpendWatch w = watch();
        w.judge("2026-11-07", 14.04, 12);
        String text = sent.get(0);
        assertTrue(text.contains("14.0 hours"));
        assertFalse(text.contains(" - ") || text.contains("—") || text.contains("–") || text.contains("·"),
                "no dashes or middle dots");
        assertFalse(text.toLowerCase().contains("network") || text.toLowerCase().contains("internet"));
    }
}
