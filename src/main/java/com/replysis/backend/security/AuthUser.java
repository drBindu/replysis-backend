package com.replysis.backend.security;

public record AuthUser(String uid, String email, String name, boolean emailVerified) {
    public AuthUser(String uid, String email, String name) {
        this(uid, email, name, false);
    }
}
