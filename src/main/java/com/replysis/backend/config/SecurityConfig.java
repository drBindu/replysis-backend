package com.replysis.backend.config;

import com.replysis.backend.security.AdminOnlyFilter;
import com.replysis.backend.security.FirebaseAuthFilter;
import com.replysis.backend.security.FirebaseAuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

@Configuration
public class SecurityConfig {

    @Bean
    public FirebaseAuthFilter firebaseAuthFilter(FirebaseAuthService service) {
        return new FirebaseAuthFilter(service);
    }

    @Bean
    public FilterRegistrationBean<FirebaseAuthFilter> firebaseFilterRegistration(
            FirebaseAuthFilter filter
    ) {
        FilterRegistrationBean<FirebaseAuthFilter> reg =
                new FilterRegistrationBean<>();

        reg.setFilter(filter);
        reg.addUrlPatterns("/admin-api/*");
        reg.setOrder(1);

        return reg;
    }

    /**
     * Only the owner reads /admin-api. The filter existed but was never registered, so any signed-in account could read the
     * business totals (2026-11-04). Runs after the token check, which puts the user in AuthContext.
     */
    @Bean
    public FilterRegistrationBean<AdminOnlyFilter> adminOnlyFilterRegistration(
            @Value("${ADMIN_FIREBASE_UID:}") String adminUid,
            @Value("${ADMIN_EMAIL:}") String adminEmail
    ) {
        FilterRegistrationBean<AdminOnlyFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new AdminOnlyFilter(adminUid, adminEmail));
        reg.addUrlPatterns("/admin-api/*");
        reg.setOrder(2);
        return reg;
    }
}
