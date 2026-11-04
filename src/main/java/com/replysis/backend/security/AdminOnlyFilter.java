package com.replysis.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class AdminOnlyFilter extends OncePerRequestFilter {

    private final String adminUid;
    private final String adminEmail;

    /** The owner is ADMIN_FIREBASE_UID when set, otherwise ADMIN_EMAIL, as the rest of the backend uses. */
    public AdminOnlyFilter(String adminUid, String adminEmail) {
        this.adminUid = adminUid;
        this.adminEmail = adminEmail;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if ("/admin-api/ping".equals(request.getRequestURI())) { chain.doFilter(request, response); return; }

        AuthUser user = AuthContext.get();
        if (user == null) {
            response.setStatus(401);
            response.getWriter().write("Unauthenticated");
            return;
        }

        // With no admin set, nobody is the admin: closed, rather than a server error.
        boolean byUid = adminUid != null && !adminUid.isBlank() && adminUid.equals(user.uid());
        boolean byEmail = (adminUid == null || adminUid.isBlank()) && adminEmail != null && !adminEmail.isBlank()
                && user.email() != null && adminEmail.trim().equalsIgnoreCase(user.email());
        if (!byUid && !byEmail) {
            response.setStatus(403);
            response.getWriter().write("Forbidden");
            return;
        }

        chain.doFilter(request, response);
    }
}
