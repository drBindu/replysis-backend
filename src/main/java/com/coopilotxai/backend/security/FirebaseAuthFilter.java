package com.coopilotxai.backend.security;

import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class FirebaseAuthFilter extends OncePerRequestFilter {

    private final FirebaseAuthService firebaseAuthService;

    public FirebaseAuthFilter(FirebaseAuthService firebaseAuthService) {
        this.firebaseAuthService = firebaseAuthService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {

        try {
            String path = request.getRequestURI();

            // ✅ allow ping without token
            if ("/admin-api/ping".equals(path)) {
                chain.doFilter(request, response);
                return;
            }

            String auth = request.getHeader(HttpHeaders.AUTHORIZATION);

            if (auth == null || !auth.startsWith("Bearer ")) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.getWriter().write("Missing Authorization: Bearer <token>");
                return;
            }

            String token = auth.substring("Bearer ".length()).trim();

            FirebaseToken decoded = firebaseAuthService.verify(token);

            AuthContext.set(new AuthUser(
                    decoded.getUid(),
                    decoded.getEmail(),
                    decoded.getName()
            ));

            chain.doFilter(request, response);

        } finally {
            AuthContext.clear();
        }
    }
}