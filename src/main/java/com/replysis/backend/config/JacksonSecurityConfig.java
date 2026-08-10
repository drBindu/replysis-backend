package com.replysis.backend.config;

import com.fasterxml.jackson.core.StreamReadConstraints;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonSecurityConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer jacksonSecurityCustomizer() {
        return builder -> builder.postConfigurer(mapper -> mapper.getFactory().setStreamReadConstraints(
                StreamReadConstraints.builder()
                        .maxNestingDepth(64)
                        .maxStringLength(200_000)
                        .maxNumberLength(256)
                        .build()
        ));
    }
}
