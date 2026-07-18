package com.coopilotxai.backend.security;

import com.google.firebase.auth.FirebaseToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

// Shared by every controller that needs to accept either a signed-in Firebase user
// OR a not-signed-in guest identified by hardware device ID (the free-trial path).
//
// SECURITY: a request that PRESENTS a Bearer token must succeed or fail on that
// token alone. Falling through to the guest path on a bad token would let an
// attacker with an expired/forged token keep full guest access — and would let
// a signed-in user with a stale token be silently double-tracked as a guest
// device. Guest identity is only considered when NO Authorization header is sent.
@Service
public class IdentityResolverService {

    // Windows MachineGuid / macOS IOPlatformUUID are 36-char UUIDs. Cap length so
    // arbitrary attacker-chosen strings can't be used as Firestore document IDs.
    private static final int MAX_DEVICE_ID_LENGTH = 64;

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
                // Invalid token = unauthenticated. Never downgrade to guest.
                return null;
            }
        }
        if (deviceIdHeader != null && !deviceIdHeader.isBlank()) {
            String deviceId = deviceIdHeader.trim();
            if (deviceId.length() > MAX_DEVICE_ID_LENGTH || !deviceId.matches("[A-Za-z0-9\\-_.]+")) {
                System.err.println("Rejected malformed device ID");
                return null;
            }
            return new RequestIdentity(null, deviceId);
        }
        return null;
    }
}
