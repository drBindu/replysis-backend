package com.replysis.backend;

import com.replysis.backend.controller.InterviewController;
import com.replysis.backend.security.*;
import com.replysis.backend.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.*;
import java.net.http.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AnswerBillingTests {
    private void exercise(boolean allowed, String responseBody) throws Exception {
        var controller = new InterviewController();
        var identity = mock(IdentityResolverService.class);
        var credits = mock(FirestoreCreditsService.class);
        var usage = mock(UsageEventService.class);
        var http = mock(HttpClient.class);
        when(identity.resolve(null, "test-device")).thenReturn(new RequestIdentity(null, "test-device"));
        when(credits.deductGuestCredits(eq("test-device"), anyInt(), any())).thenAnswer(call -> {
            Thread.sleep(100); // Provider failure can outrun an asynchronous charge.
            return allowed;
        });
        if (responseBody == null) {
            when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenThrow(new IOException("synthetic provider connection failure"));
        } else {
            when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
                HttpResponse<InputStream> response = mock(HttpResponse.class);
                when(response.statusCode()).thenReturn(200);
                when(response.body()).thenReturn(new ByteArrayInputStream(responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                return response;
            });
        }
        ReflectionTestUtils.setField(controller, "identityResolver", identity);
        ReflectionTestUtils.setField(controller, "creditsService", credits);
        ReflectionTestUtils.setField(controller, "usageEvents", usage);
        ReflectionTestUtils.setField(controller, "rateLimiter", new SimpleRateLimiter());
        ReflectionTestUtils.setField(controller, "geminiApiKey", "synthetic-test-key");
        ReflectionTestUtils.setField(controller, "httpClient", http);
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        var result = controller.askQuestion(null, "test-device", request, Map.of("question", "Explain database indexes"));
        assertNotNull(result.getBody());
        result.getBody().writeTo(new ByteArrayOutputStream());
        verify(credits, timeout(1000)).deductGuestCredits(eq("test-device"), anyInt(), any());
        if (allowed && (responseBody == null || responseBody.isEmpty())) {
            verify(credits, timeout(1000).times(1)).refundGuestCredits(eq("test-device"), anyInt(), any());
        } else {
            verify(credits, never()).refundGuestCredits(anyString(), anyInt(), any());
        }
        verify(credits, times(1)).deductGuestCredits(eq("test-device"), anyInt(), any());
        if (allowed && "".equals(responseBody))
            verify(http, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test void providerFailureAfterSlowChargeRefundsExactlyOnce() throws Exception { exercise(true, null); }
    @Test void rejectedChargeGrantsNoRefund() throws Exception { exercise(false, null); }
    @Test void emptyProviderRetriesUnderOneChargeThenRefundsOnce() throws Exception { exercise(true, ""); }
    @Test void deliveredAnswerIsChargedOnceWithoutRefund() throws Exception {
        exercise(true, "data: {\"choices\":[{\"delta\":{\"content\":\"An index speeds up database lookups.\"}}]}\n\ndata: [DONE]\n\n");
    }

    /** An output stream whose reader walks away after the first write. */
    private static class WalksAway extends OutputStream {
        final int maxWrites;
        int writes = 0;
        WalksAway(int maxWrites) { this.maxWrites = maxWrites; }
        @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            if (++writes > maxWrites) throw new IOException("client closed the connection");
        }
    }

    @Test void aClientThatLeavesMidAnswerStillPaysForIt() throws Exception { leaves(3, false); }

    @Test void aClientThatLeavesAfterAFewWordsIsRefunded() throws Exception { leaves(1, true); }

    private void leaves(int maxWrites, boolean refundExpected) throws Exception {
        var controller = new InterviewController();
        var identity = mock(IdentityResolverService.class);
        var credits = mock(FirestoreCreditsService.class);
        var usage = mock(UsageEventService.class);
        var http = mock(HttpClient.class);
        when(identity.resolve(null, "test-device")).thenReturn(new RequestIdentity(null, "test-device"));
        when(credits.deductGuestCredits(eq("test-device"), anyInt(), any())).thenReturn(true);
        StringBuilder sse = new StringBuilder();
        for (int i = 0; i < 60; i++)
            sse.append("data: {\"choices\":[{\"delta\":{\"content\":\"An index speeds up lookups by keeping keys sorted. \"}}]}\n\n");
        sse.append("data: [DONE]\n\n");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            HttpResponse<InputStream> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.body()).thenReturn(new ByteArrayInputStream(sse.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return response;
        });
        ReflectionTestUtils.setField(controller, "identityResolver", identity);
        ReflectionTestUtils.setField(controller, "creditsService", credits);
        ReflectionTestUtils.setField(controller, "usageEvents", usage);
        ReflectionTestUtils.setField(controller, "rateLimiter", new SimpleRateLimiter());
        ReflectionTestUtils.setField(controller, "geminiApiKey", "synthetic-test-key");
        ReflectionTestUtils.setField(controller, "httpClient", http);
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        var result = controller.askQuestion(null, "test-device", request, Map.of("question", "Explain database indexes"));
        result.getBody().writeTo(new WalksAway(maxWrites));
        verify(credits, timeout(1000)).deductGuestCredits(eq("test-device"), anyInt(), any());
        Thread.sleep(300);
        if (refundExpected) verify(credits, times(1)).refundGuestCredits(eq("test-device"), anyInt(), any());
        else verify(credits, never()).refundGuestCredits(anyString(), anyInt(), any());
    }
}
