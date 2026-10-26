package com.replysis.backend.controller;

import com.google.cloud.firestore.FieldValue;
import com.google.firebase.cloud.FirestoreClient;
import com.replysis.backend.security.AppInfo;
import com.replysis.backend.security.IdentityResolverService;
import com.replysis.backend.security.RequestIdentity;
import com.replysis.backend.security.SimpleRateLimiter;
import com.replysis.backend.service.AlertService;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * POST /api/v1/account/delete-request: a person asking, inside an app, for their account to be deleted (2026-10-26).
 *
 * Apple requires an app that creates accounts to let people start deleting them in the app. Deleting everything at once
 * (sign-in, records, saved resumes, any subscription) stays a deliberate step done from the admin page, which already does
 * it properly; this records the request in deletion_requests/{uid} and emails support, and the app tells the person it
 * is completed within 7 days. Asking twice is harmless: the same record, one email.
 */
@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

    @Autowired private IdentityResolverService identityResolver;
    @Autowired private SimpleRateLimiter rateLimiter;
    @Autowired(required = false) private AlertService alerts;

    @PostMapping("/delete-request")
    public ResponseEntity<?> deleteRequest(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                           HttpServletRequest request) {
        RequestIdentity identity = identityResolver.resolve(authHeader, null);
        if (identity == null || identity.isGuest()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (!rateLimiter.tryAcquire("delete-request:" + identity.uid(), 5, 3_600_000L))
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        String email = "";
        try {
            var db = FirestoreClient.getFirestore();
            Object e = db.collection("users").document(identity.uid()).get().get(8, TimeUnit.SECONDS).get("email");
            email = e instanceof String s ? s : "";
            AppInfo app = AppInfo.current();
            Map<String, Object> doc = new HashMap<>();
            doc.put("uid", identity.uid());
            doc.put("email", email);
            doc.put("requestedAt", FieldValue.serverTimestamp());
            doc.put("from", app == null ? "unknown" : app.platform() + " " + app.version());
            doc.put("status", "requested");
            db.collection("deletion_requests").document(identity.uid()).set(doc).get(8, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("[ACCOUNT] delete request could not be recorded: " + e.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Your request could not be sent just now. Try again in a moment."));
        }
        System.out.println("[ACCOUNT] deletion requested by uid=" + identity.uid());
        if (alerts != null) {
            alerts.notice("delete-request-" + identity.uid(), "Replysis: account deletion requested",
                    "A customer asked in the app to delete their account.\n\nEmail: " + (email.isEmpty() ? "(none on record)" : email)
                            + "\nAccount id: " + identity.uid()
                            + "\n\nThey were told it is completed within 7 days. Delete it from https://replysis.com/admin (People),"
                            + " and cancel any subscription in Stripe first.");
        }
        return ResponseEntity.ok(Map.of("requested", true, "days", 7));
    }
}
