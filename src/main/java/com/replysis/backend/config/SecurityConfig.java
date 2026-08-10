package com.replysis.backend.config;

import com.replysis.backend.security.FirebaseAuthFilter;
import com.replysis.backend.security.FirebaseAuthService;
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
}