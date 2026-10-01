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
}
