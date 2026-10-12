package com.replysis.backend.controller;

import com.google.firebase.auth.FirebaseToken;
import com.replysis.backend.security.FirebaseAuthService;
import com.replysis.backend.security.SimpleRateLimiter;
import com.replysis.backend.service.FirestoreCreditsService;
import com.replysis.backend.service.QuestionGenerationService;
import com.replysis.backend.service.QuestionGenerationService.GenerationException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The website's mock interview: charged once for a set that arrived, never charged for one that did not, and told why. */
class QuestionControllerTests {

    private static final String RESUME = "Senior engineer, built payment systems in Java and Kotlin for six years.";

    private QuestionController controller;
    private FirebaseAuthService auth;
    private FirestoreCreditsService credits;
    private QuestionGenerationService generator;
    private SimpleRateLimiter limiter;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        controller = new QuestionController();
        auth = mock(FirebaseAuthService.class);
        credits = mock(FirestoreCreditsService.class);
        generator = mock(QuestionGenerationService.class);
        limiter = mock(SimpleRateLimiter.class);
        request = mock(HttpServletRequest.class);
        ReflectionTestUtils.setField(controller, "firebaseAuthService", auth);
        ReflectionTestUtils.setField(controller, "creditsService", credits);
        ReflectionTestUtils.setField(controller, "generator", generator);
        ReflectionTestUtils.setField(controller, "rateLimiter", limiter);

        FirebaseToken token = mock(FirebaseToken.class);
        when(token.getUid()).thenReturn("user-1");
        when(token.getEmail()).thenReturn("a@example.com");
        when(auth.verify("good")).thenReturn(token);
        when(auth.verify("bad")).thenThrow(new RuntimeException("Invalid Firebase token"));
        when(limiter.clientIp(any())).thenReturn("203.0.113.9");
        when(limiter.tryAcquire(anyString(), anyInt(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        when(generator.configured()).thenReturn(true);
        when(credits.deductCredits(eq("user-1"), eq(5), any())).thenReturn(true);
    }

    private static Map<String, Object> body() {
        return Map.of("resume", RESUME, "jd", "Backend engineer", "count", 50, "difficulty", "medium");
    }

    private static List<String> fifty() {
        List<String> q = new ArrayList<>();
        for (int i = 1; i <= 50; i++) q.add("Question " + i + "?");
        return q;
    }

    private ResponseEntity<Map<String, Object>> call(String header, Map<String, Object> body) {
        return controller.generate(header, body, request);
    }

    @Test
    void aSignedInPersonGetsTheirQuestionsAndIsChargedOnce() throws Exception {
        when(generator.generate(any(), any(), eq(50), any())).thenReturn(fifty());
        ResponseEntity<Map<String, Object>> r = call("Bearer good", body());
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals(50, ((List<?>) r.getBody().get("questions")).size());
        verify(credits, times(1)).deductCredits(eq("user-1"), eq(5), any());
        verify(credits, never()).refundCredits(anyString(), anyInt(), any());
    }

    @Test
    void noTokenMeansNoWorkAndNoCharge() throws Exception {
        ResponseEntity<Map<String, Object>> r = call(null, body());
        assertEquals(HttpStatus.UNAUTHORIZED, r.getStatusCode());
        assertEquals("not_signed_in", r.getBody().get("error"));
        verify(credits, never()).deductCredits(anyString(), anyInt(), any());
        verify(generator, never()).generate(any(), any(), anyInt(), any());
    }

    @Test
    void aBadTokenMeansNoWorkAndNoCharge() throws Exception {
        ResponseEntity<Map<String, Object>> r = call("Bearer bad", body());
        assertEquals(HttpStatus.UNAUTHORIZED, r.getStatusCode());
        verify(credits, never()).deductCredits(anyString(), anyInt(), any());
    }

    @Test
    void tooManyTriesIsRefusedBeforeAnyCharge() throws Exception {
        when(limiter.tryAcquire(anyString(), anyInt(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(false);
        ResponseEntity<Map<String, Object>> r = call("Bearer good", body());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, r.getStatusCode());
        assertEquals("rate_limited", r.getBody().get("error"));
        verify(credits, never()).deductCredits(anyString(), anyInt(), any());
    }

    @Test
    void aMissingResumeIsRefusedBeforeAnyCharge() {
        ResponseEntity<Map<String, Object>> r = call("Bearer good", Map.of("jd", "Backend engineer"));
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertEquals("resume_missing", r.getBody().get("error"));
        verify(credits, never()).deductCredits(anyString(), anyInt(), any());
    }

    @Test
    void notEnoughCreditsIsA402AndNothingIsGenerated() throws Exception {
        when(credits.deductCredits(eq("user-1"), eq(5), any())).thenReturn(false);
        ResponseEntity<Map<String, Object>> r = call("Bearer good", body());
        assertEquals(HttpStatus.PAYMENT_REQUIRED, r.getStatusCode());
        assertEquals("insufficient_credits", r.getBody().get("error"));
        verify(generator, never()).generate(any(), any(), anyInt(), any());
        verify(credits, never()).refundCredits(anyString(), anyInt(), any());
    }

    @Test
    void aServerWithNoModelKeyIsSaidSoAndNobodyIsCharged() throws Exception {
        when(generator.configured()).thenReturn(false);
        ResponseEntity<Map<String, Object>> r = call("Bearer good", body());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, r.getStatusCode());
        assertEquals("question_service_not_configured", r.getBody().get("error"));
        verify(credits, never()).deductCredits(anyString(), anyInt(), any());
        verify(generator, never()).generate(any(), any(), anyInt(), any());
    }

    @Test
    void aProviderOutageRefundsOnceAndSaysSo() throws Exception {
        when(generator.generate(any(), any(), anyInt(), any()))
                .thenThrow(new GenerationException(GenerationException.Kind.PROVIDER_UNAVAILABLE, "down"));
        ResponseEntity<Map<String, Object>> r = call("Bearer good", body());
        assertEquals(HttpStatus.BAD_GATEWAY, r.getStatusCode());
        assertEquals("provider_unavailable", r.getBody().get("error"));
        assertEquals(true, r.getBody().get("creditsRefunded"));
        verify(credits, times(1)).deductCredits(eq("user-1"), eq(5), any());
        verify(credits, times(1)).refundCredits(eq("user-1"), eq(5), any());
    }

    @Test
    void aThinAnswerRefundsOnceAndSaysSo() throws Exception {
        when(generator.generate(any(), any(), anyInt(), any()))
                .thenThrow(new GenerationException(GenerationException.Kind.BAD_OUTPUT, "thin"));
        ResponseEntity<Map<String, Object>> r = call("Bearer good", body());
        assertEquals(HttpStatus.BAD_GATEWAY, r.getStatusCode());
        assertEquals("bad_output", r.getBody().get("error"));
        verify(credits, times(1)).refundCredits(eq("user-1"), eq(5), any());
    }

    @Test
    void anUnexpectedErrorStillRefunds() throws Exception {
        when(generator.generate(any(), any(), anyInt(), any())).thenThrow(new IllegalStateException("boom"));
        ResponseEntity<Map<String, Object>> r = call("Bearer good", body());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, r.getStatusCode());
        assertEquals("question_service_error", r.getBody().get("error"));
        verify(credits, times(1)).refundCredits(eq("user-1"), eq(5), any());
    }

    @Test
    void aFailedRefundDoesNotHideTheRealError() throws Exception {
        when(generator.generate(any(), any(), anyInt(), any()))
                .thenThrow(new GenerationException(GenerationException.Kind.PROVIDER_UNAVAILABLE, "down"));
        org.mockito.Mockito.doThrow(new RuntimeException("firestore down")).when(credits).refundCredits(anyString(), anyInt(), any());
        ResponseEntity<Map<String, Object>> r = call("Bearer good", body());
        assertEquals("provider_unavailable", r.getBody().get("error"));
    }

    @Test
    void theCountIsKeptBetweenTenAndFifty() {
        assertEquals(50, QuestionController.clampCount(null));
        assertEquals(50, QuestionController.clampCount(500));
        assertEquals(10, QuestionController.clampCount(1));
        assertEquals(25, QuestionController.clampCount("25"));
        assertEquals(50, QuestionController.clampCount("lots"));
    }
}
