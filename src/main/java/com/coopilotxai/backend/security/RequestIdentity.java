package com.coopilotxai.backend.security;

// Either a signed-in Firebase uid, or a guest device ID. Exactly one of uid/deviceId
// is non-null. Lets every credit check/deduct call site pick the right storage (real
// user vs. free-trial-by-device) without duplicating the auth-vs-guest branching.
public record RequestIdentity(String uid, String deviceId) {
    public boolean isGuest() { return uid == null; }
    @Override public String toString() { return isGuest() ? "device=" + deviceId : "uid=" + uid; }
}
