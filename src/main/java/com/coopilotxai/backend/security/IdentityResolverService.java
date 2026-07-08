package com.coopilotxai.backend.security;

import com.google.firebase.auth.FirebaseToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

// Shared by every controller that needs to accept either a signed-in Firebase user
// OR a not-signed-in guest identified by hardware device ID (the free-trial path). A
// real Bearer token always wins when present and valid; X-Device-Id is only consulted
// when there's no valid token, so a signed-in user is never accidentally treated as a
// guest just because both headers happen to be sent.
@Service
public class IdentityResolverService {

    @Autowired
    private FirebaseAuthService firebaseAuthService;

    public RequestIdentity resolve(String authHeader, String deviceIdHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            try {
                String token = authHeader.substring("Bearer ".length()).trim();
                FirebaseToken decoded = firebaseAuthService.verify(token);
                return new RequestIdentity(decoded.getUid(), null);
            } catch (Exception e) {
                System.err.println("Token verification failed: " + e.getMessage());
            }
        }
        if (deviceIdHeader != null && !deviceIdHeader.isBlank()) {
            return new RequestIdentity(null, deviceIdHeader.trim());
        }
        return null;
    }
}
