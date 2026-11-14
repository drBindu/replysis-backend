package com.replysis.backend.service;

import com.replysis.backend.security.AppInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AppDiaryServiceTests {

    private static Map<String, Object> event(String name, Map<String, Object> props) {
        Map<String, Object> e = new HashMap<>();
        e.put("t", System.currentTimeMillis());
        e.put("session_id", "abc-123");
        e.put("event", name);
        e.put("props", props);
        return e;
    }

    @Test
    void keepsNamesNumbersAndShortTokens() {
        var out = AppDiaryService.clean(List.of(event("answer_shown",
                Map.of("ms_to_first_words", 980, "source", "voice", "stealth", true))));
        assertEquals(1, out.size());
        Map<?, ?> props = (Map<?, ?>) out.get(0).get("props");
        assertEquals(980L, props.get("ms_to_first_words"));
        assertEquals("voice", props.get("source"));
        assertEquals(true, props.get("stealth"));
    }

    @Test
    void dropsAnythingThatLooksLikeWordsOrContent() {
        Map<String, Object> props = new HashMap<>();
        props.put("question", "Tell me about a time you disagreed");   // a sentence
        props.put("text", "x".repeat(41));                             // too long
        props.put("Bad Key", "ok");                                    // key not a plain name
        props.put("list", List.of("a"));                               // not a value
        props.put("kind", "cannot-reach-the-speech-service");          // fine
        var out = AppDiaryService.clean(List.of(event("problem_shown", props)));
        Map<?, ?> kept = (Map<?, ?>) out.get(0).get("props");
        assertEquals(Map.of("kind", "cannot-reach-the-speech-service"), kept);
    }

    @Test
    void dropsBadEventNamesAndCapsTheBatch() {
        List<Object> raw = new ArrayList<>();
        raw.add(event("Hello world", Map.of()));
        raw.add("not an event");
        for (int i = 0; i < 250; i++) raw.add(event("button_clicked", Map.of("id", "copy")));
        var out = AppDiaryService.clean(raw);
        assertEquals(AppDiaryService.MAX_EVENTS, out.size());
        assertTrue(out.stream().allMatch(e -> "button_clicked".equals(e.get("event"))));
    }

    @Test
    void aClockFarOffIsReplacedByArrivalTime() {
        Map<String, Object> e = event("app_opened", Map.of());
        e.put("t", 5L);
        long before = System.currentTimeMillis();
        var out = AppDiaryService.clean(List.of(e));
        assertTrue((Long) out.get(0).get("t") >= before);
    }

    @Test
    void theBatchBelongsToTheTokenNotTheBody() {
        Map<String, Object> e = event("app_opened", Map.of());
        e.put("uid", "someone-else");
        var events = AppDiaryService.clean(List.of(e));
        var doc = AppDiaryService.batchDoc("me", new AppInfo("mac", "1.0.256"), events, 1000L);
        assertEquals("me", doc.get("uid"));
        assertEquals("mac", doc.get("platform"));
        assertEquals("abc-123", doc.get("sessionId"));
        assertEquals(1, doc.get("count"));
        List<?> stored = (List<?>) doc.get("events");
        assertFalse(((Map<?, ?>) stored.get(0)).containsKey("uid"));
    }
}
