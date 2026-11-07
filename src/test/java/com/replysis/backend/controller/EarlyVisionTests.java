package com.replysis.backend.controller;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLSession;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The picture answer starts beside the screen read (2026-11-07), so a screen with no coding problem no longer waits
 * for a read that is thrown away. These pin when it starts and that an unused one is always closed.
 */
class EarlyVisionTests {

    @Test
    void aReadFinishedAheadMeansNoEarlyStart() {
        assertTrue(InterviewController.readReady(CompletableFuture.completedFuture("PROBLEM: two sum")));
    }

    @Test
    void aReadStillRunningEmptyOrFailedStillStartsTheAnswerEarly() {
        assertFalse(InterviewController.readReady(null));
        assertFalse(InterviewController.readReady(new CompletableFuture<>()));
        assertFalse(InterviewController.readReady(CompletableFuture.completedFuture(null)));
        assertFalse(InterviewController.readReady(CompletableFuture.completedFuture("  ")));
        assertFalse(InterviewController.readReady(CompletableFuture.failedFuture(new RuntimeException("x"))));
    }

    @Test
    void anUnusedEarlyAnswerIsClosedEvenIfItArrivesLater() {
        AtomicBoolean closed = new AtomicBoolean(false);
        CompletableFuture<HttpResponse<InputStream>> early = new CompletableFuture<>();
        InterviewController.discardEarlyVision(early);
        assertFalse(closed.get());
        early.complete(response(closed));
        assertTrue(closed.get());
    }

    @Test
    void anUnusedEarlyAnswerThatFailedIsLeftAlone() {
        CompletableFuture<HttpResponse<InputStream>> early = CompletableFuture.failedFuture(new RuntimeException("x"));
        InterviewController.discardEarlyVision(early);   // must not throw
    }

    private static HttpResponse<InputStream> response(AtomicBoolean closed) {
        InputStream body = new ByteArrayInputStream(new byte[0]) {
            @Override public void close() { closed.set(true); }
        };
        return new HttpResponse<>() {
            @Override public int statusCode() { return 200; }
            @Override public HttpRequest request() { return null; }
            @Override public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
            @Override public InputStream body() { return body; }
            @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return URI.create("https://example.invalid"); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }
}
