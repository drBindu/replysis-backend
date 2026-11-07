package com.replysis.backend.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** The charge for a voice answer runs on a pool thread; its usage row must still say which app asked. */
class AppInfoCarryTests {

    @AfterEach
    void clear() { RequestContextHolder.resetRequestAttributes(); }

    @Test
    void appIsKnownOnThePoolThreadThatChargesTheAnswer() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-App-Platform", "windows");
        request.addHeader("X-App-Version", "1.0.52");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            assertNull(CompletableFuture.supplyAsync(AppInfo::current, pool).get(), "a bare pool thread has no request");
            AppInfo carried = CompletableFuture.supplyAsync(AppInfo.carriedTo(AppInfo::current), pool).get();
            assertEquals(new AppInfo("windows", "1.0.52"), carried);
            assertNull(CompletableFuture.supplyAsync(AppInfo::current, pool).get(), "nothing is left behind on the pool thread");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void outsideARequestNothingIsCarried() throws Exception {
        assertNull(CompletableFuture.supplyAsync(AppInfo.carriedTo(AppInfo::current)).get());
    }
}
