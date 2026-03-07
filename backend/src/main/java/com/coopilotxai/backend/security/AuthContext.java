package com.coopilotxai.backend.security;

public class AuthContext {
    private static final ThreadLocal<AuthUser> CURRENT = new ThreadLocal<>();

    public static void set(AuthUser user) { CURRENT.set(user); }
    public static AuthUser get() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }
}
