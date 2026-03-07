package com.coopilotxai.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class AdminOnlyFilter extends OncePerRequestFilter {

    private final String adminUid;

    public AdminOnlyFilter(String adminUid) {
        this.adminUid = adminUid;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        AuthUser user = AuthContext.get();
        if (user == null) {
            response.setStatus(401);
            response.getWriter().write("Unauthenticated");
            return;
        }

        if (adminUid == null || adminUid.isBlank()) {
            response.setStatus(500);
            response.getWriter().write("ADMIN_FIREBASE_UID not configured");
            return;
        }

        if (!adminUid.equals(user.uid())) {
            response.setStatus(403);
            response.getWriter().write("Forbidden");
            return;
        }

        chain.doFilter(request, response);
    }
}
