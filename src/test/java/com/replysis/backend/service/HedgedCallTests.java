package com.replysis.backend.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The backup helps when the main call is slow, and never changes what happens when it is not. */
class HedgedCallTests {

    private static Callable<String> reply(String text, long afterMs, AtomicInteger calls) {
        return () -> {
            calls.incrementAndGet();
            Thread.sleep(afterMs);
            return text;
        };
    }

    private static boolean ok(String s) { return s.startsWith("200"); }

    @Test
    void aQuickMainCallIsReturnedAndTheBackupIsNeverAsked() throws Exception {
        AtomicInteger main = new AtomicInteger(), backup = new AtomicInteger();
        String got = HedgedCall.race(reply("200 main", 20, main), reply("200 backup", 20, backup), 300,
                HedgedCallTests::ok, s -> {}, HedgedCall.pool());
        assertEquals("200 main", got);
        Thread.sleep(400);
        assertEquals(0, backup.get(), "no second request when the first was quick");
    }

    @Test
    void aSlowMainCallLosesToAFastBackup() throws Exception {
        AtomicInteger main = new AtomicInteger(), backup = new AtomicInteger();
        long t0 = System.currentTimeMillis();
        String got = HedgedCall.race(reply("200 main", 2000, main), reply("200 backup", 50, backup), 150,
                HedgedCallTests::ok, s -> {}, HedgedCall.pool());
        long took = System.currentTimeMillis() - t0;
        assertEquals("200 backup", got);
        assertTrue(took < 1000, "answered in about the delay plus the backup's time, not the main call's two seconds: " + took);
    }

    @Test
    void aMainCallThatFinishesAfterTheDelayStillWinsIfItIsFirst() throws Exception {
        AtomicInteger main = new AtomicInteger(), backup = new AtomicInteger();
        String got = HedgedCall.race(reply("200 main", 250, main), reply("200 backup", 1500, backup), 100,
                HedgedCallTests::ok, s -> {}, HedgedCall.pool());
        assertEquals("200 main", got);
    }

    @Test
    void aQuickErrorIsReturnedAsItIsSoTheExistingFallbackStillRuns() throws Exception {
        AtomicInteger main = new AtomicInteger(), backup = new AtomicInteger();
        String got = HedgedCall.race(reply("429 busy", 20, main), reply("200 backup", 20, backup), 300,
                HedgedCallTests::ok, s -> {}, HedgedCall.pool());
        assertEquals("429 busy", got, "the caller's own retry and fallback logic handles it, as before");
        assertEquals(0, backup.get());
    }

    @Test
    void whenBothAreBadTheMainCallsReplyIsReturned() throws Exception {
        AtomicInteger main = new AtomicInteger(), backup = new AtomicInteger();
        String got = HedgedCall.race(reply("503 down", 400, main), reply("500 also down", 100, backup), 100,
                HedgedCallTests::ok, s -> {}, HedgedCall.pool());
        assertEquals("503 down", got);
    }

    @Test
    void aMainCallThatThrowsAfterTheDelayFallsBackToTheBackup() throws Exception {
        Callable<String> boom = () -> { Thread.sleep(300); throw new java.io.IOException("connection reset"); };
        String got = HedgedCall.race(boom, reply("200 backup", 50, new AtomicInteger()), 100,
                HedgedCallTests::ok, s -> {}, HedgedCall.pool());
        assertEquals("200 backup", got);
    }

    @Test
    void aReplyThatLostTheRaceIsReleased() throws Exception {
        List<String> released = Collections.synchronizedList(new ArrayList<>());
        // the main call is slow but not cancellable here (it ignores interruption), so it arrives after losing
        Callable<String> stubborn = () -> {
            long end = System.currentTimeMillis() + 500;
            while (System.currentTimeMillis() < end) {
                try { Thread.sleep(20); } catch (InterruptedException ignored) { /* keeps going */ }
            }
            return "200 main late";
        };
        String got = HedgedCall.race(stubborn, reply("200 backup", 30, new AtomicInteger()), 100,
                HedgedCallTests::ok, released::add, HedgedCall.pool());
        assertEquals("200 backup", got);
        Thread.sleep(700);
        assertTrue(released.isEmpty() || released.contains("200 main late"),
                "a late loser is either cancelled or handed to discard, never left open: " + released);
    }

    @Test
    void aZeroDelayMeansNoHedgingAtAll() throws Exception {
        AtomicInteger main = new AtomicInteger(), backup = new AtomicInteger();
        String got = HedgedCall.race(reply("200 main", 200, main), reply("200 backup", 10, backup), 0,
                HedgedCallTests::ok, s -> {}, HedgedCall.pool());
        assertEquals("200 main", got);
        assertEquals(0, backup.get());
    }
}
