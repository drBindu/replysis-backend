package com.replysis.backend;

import com.replysis.backend.controller.SttController;
import com.replysis.backend.security.*;
import com.replysis.backend.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A refusal must never use up the budget that stops token farming.
 *
 * 2026-09-29: a Free tester was told "no listening time" (402) and her app asked again every 30
 * seconds. The strict limit of 12 requests an hour counted refused requests too, so twelve
 * refusals in six minutes used the whole hour and every later reply was 429 "too many requests",
 * which hid the real reason. It would have happened to any user refused a few times.
 */
class SttKeyOrderTests {

    private SttController controller(FirestoreCreditsService credits) {
        var controller = new SttController();
        var identity = mock(IdentityResolverService.class);
        when(identity.resolve(any(), any())).thenReturn(new RequestIdentity("uid-1", null));
        ReflectionTestUtils.setField(controller, "identityResolver", identity);
        ReflectionTestUtils.setField(controller, "creditsService", credits);
        ReflectionTestUtils.setField(controller, "rateLimiter", new SimpleRateLimiter());
        ReflectionTestUtils.setField(controller, "speechmaticsApiKey", "");   // stops before any network call
        return controller;
    }

    private ResponseEntity<?> ask(SttController controller) {
        return controller.getSttKey("Bearer x", null, new MockHttpServletRequest());
    }

    @Test
    void anAccountWithNoListeningTimeIsToldSoEveryTimeAndNeverRateLimited() {
        var credits = mock(FirestoreCreditsService.class);
        when(credits.canAfford("uid-1")).thenReturn(true);
        when(credits.hasAudioTimeLeft("uid-1")).thenReturn(false);
        var controller = controller(credits);

        for (int i = 1; i <= 60; i++) {
            var response = ask(controller);
            assertEquals(HttpStatus.PAYMENT_REQUIRED, response.getStatusCode(),
                    "request " + i + " must still be the real reason, not a rate limit");
            assertEquals("audio-limit", ((Map<?, ?>) response.getBody()).get("reason"));
        }
    }

    @Test
    void anAccountWithNoCreditsIsToldSoEveryTimeAndNeverRateLimited() {
        var credits = mock(FirestoreCreditsService.class);
        when(credits.canAfford("uid-1")).thenReturn(false);
        var controller = controller(credits);

        for (int i = 1; i <= 60; i++)
            assertEquals(HttpStatus.PAYMENT_REQUIRED, ask(controller).getStatusCode(), "request " + i);
    }

    @Test
    void refusalsDoNotSpendTheMintBudgetSoARestoredAccountCanStartAtOnce() {
        var credits = mock(FirestoreCreditsService.class);
        when(credits.canAfford("uid-1")).thenReturn(true);
        when(credits.hasAudioTimeLeft("uid-1")).thenReturn(false);
        var controller = controller(credits);
        for (int i = 0; i < 40; i++) ask(controller);            // refused forty times

        when(credits.hasAudioTimeLeft("uid-1")).thenReturn(true); // then the allowance is restored
        var response = ask(controller);

        // Past every limit. It stops at "key not configured" only because this test gives it none.
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    }

    @Test
    void theStrictLimitStillStopsTokenFarming() {
        var credits = mock(FirestoreCreditsService.class);
        when(credits.canAfford("uid-1")).thenReturn(true);
        when(credits.hasAudioTimeLeft("uid-1")).thenReturn(true);
        var controller = controller(credits);

        int mintsAttempted = 0;
        HttpStatus last = null;
        for (int i = 0; i < 20; i++) {
            var response = ask(controller);
            last = HttpStatus.valueOf(response.getStatusCode().value());
            if (last == HttpStatus.TOO_MANY_REQUESTS) break;
            mintsAttempted++;
        }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, last, "farming is still stopped");
        assertTrue(mintsAttempted <= 5, "by the per-address ceiling of five a minute");
    }
}
