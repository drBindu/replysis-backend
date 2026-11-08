package com.replysis.backend.controller;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A screen read stops at its verdict (2026-11-08): on an ordinary screen the read only says "none: not a coding screen",
 * and streaming the rest of it held the already started answer back by 1 to 2 s. These pin that it stops there, that a
 * coding read is read to the end, and that the body is closed so the model stops writing.
 */
class ScreenReadVerdictTests {

    /** Each line's payload is the token itself, so the tests need no JSON. */
    private static final Function<String, String> TOKEN = line -> line.substring("data: ".length());

    @Test
    void anOrdinaryScreenStopsAtTheVerdict() throws Exception {
        AtomicInteger linesRead = new AtomicInteger();
        AtomicBoolean closed = new AtomicBoolean();
        String read = InterviewController.collectRead(
                sse(closed, "PROBLEM: ", "none: not a coding ", "screen. It is a ", "video call with ", "a slide."),
                line -> { linesRead.incrementAndGet(); return TOKEN.apply(line); });
        assertTrue(read.toLowerCase().contains(InterviewController.NOT_CODING_MARKER));
        assertEquals(3, linesRead.get(), "stops at the token that completes the verdict");
        assertTrue(closed.get(), "the model's stream is closed");
    }

    @Test
    void aCodingScreenIsReadToTheEnd() throws Exception {
        String read = InterviewController.collectRead(
                sse(new AtomicBoolean(), "PROBLEM: Two Sum. ", "Given an array ", "LANGUAGE: Java\n",
                        "ERROR: none\n", "CONSTRAINTS: 1 <= n <= 10^4"),
                TOKEN);
        assertTrue(read.endsWith("10^4"));
        assertFalse(read.toLowerCase().contains(InterviewController.NOT_CODING_MARKER));
    }

    @Test
    void theWordsLaterInACodingReadAreNotTakenForTheVerdict() {
        // ERROR: none and LANGUAGE: none must never count; only the exact marker does.
        assertFalse(InterviewController.saysNotCoding("PROBLEM: Two Sum\nLANGUAGE: none\nERROR: none\n"));
        assertTrue(InterviewController.saysNotCoding("PROBLEM: None: Not a coding screen, a dashboard"));
        assertFalse(InterviewController.saysNotCoding(null));
    }

    @Test
    void aMarkerFarIntoTheReadIsNotAVerdict() {
        String longCodingRead = "PROBLEM: " + "x".repeat(InterviewController.VERDICT_WINDOW_CHARS + 300)
                + " none: not a coding screen";
        assertFalse(InterviewController.saysNotCoding(longCodingRead));
    }

    @Test
    void thePictureAnswerStartsAtOnceUnlessAReadAheadFoundCode() {
        var notCoding = java.util.List.of(stashed("PROBLEM: none: not a coding screen, a slide"));
        var coding = java.util.List.of(stashed("PROBLEM: Two Sum"));
        var none = java.util.List.<InterviewController.StashedImage>of();
        assertFalse(InterviewController.startPictureAnswerEarly(true, true, none), "words, not a picture");
        assertTrue(InterviewController.startPictureAnswerEarly(false, false, none), "not about code: no read at all");
        assertTrue(InterviewController.startPictureAnswerEarly(false, true, none), "read still to come");
        assertTrue(InterviewController.startPictureAnswerEarly(false, true, notCoding), "read ahead says no code");
        assertFalse(InterviewController.startPictureAnswerEarly(false, true, coding), "read ahead found code");
    }

    @Test
    void theVerdictIsTheFirstHeadingSoItArrivesWithTheFirstWords() {
        String t = InterviewController.SCREEN_READ_TEMPLATE;
        int kind = t.indexOf("KIND:"), problem = t.indexOf("PROBLEM:");
        assertTrue(kind >= 0 && kind < problem, "KIND comes before PROBLEM");
        assertTrue(t.substring(kind, problem).contains(InterviewController.NOT_CODING_MARKER),
                "the not coding verdict is written under KIND");
        assertFalse(t.substring(problem).contains(InterviewController.NOT_CODING_MARKER),
                "PROBLEM does not repeat the marker, so a coding read can never trip it there");
    }

    @Test
    void aKindVerdictStopsTheReadAndACodingKindDoesNot() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        String ordinary = InterviewController.collectRead(
                sse(closed, "KIND: none: not a ", "coding screen", " PROBLEM: none, a slide about churn"), TOKEN);
        assertTrue(closed.get());
        assertFalse(ordinary.contains("churn"), "stops at the verdict");
        assertTrue(InterviewController.saysNotCoding(ordinary));

        String coding = InterviewController.collectRead(
                sse(new AtomicBoolean(), "KIND: coding", " PROBLEM: none", " ERROR: ZeroDivisionError on line 14"), TOKEN);
        assertTrue(coding.contains("ZeroDivisionError"), "a coding read is read to the end");
        assertFalse(InterviewController.saysNotCoding(coding));
    }

    private static InterviewController.StashedImage stashed(String read) {
        return new InterviewController.StashedImage("u", "x", null, Long.MAX_VALUE,
                java.util.concurrent.CompletableFuture.completedFuture(read));
    }

    private static InputStream sse(AtomicBoolean closed, String... tokens) {
        var body = new StringBuilder();
        for (String t : tokens) body.append("data: ").append(t).append('\n').append('\n');
        return new ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8)) {
            @Override public void close() { closed.set(true); }
        };
    }
}
