package com.replysis.backend.config;

import com.fasterxml.jackson.core.StreamReadConstraints;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonSecurityConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer jacksonSecurityCustomizer() {
        // These are abuse ceilings, not size targets. A real interview request
        // carries the resume plus the whole running transcript in one string, and
        // was measured at ~260k characters, so a 200k cap rejected legitimate
        // traffic. 2M keeps the parser bounded while leaving room for long sessions.
        return builder -> builder.postConfigurer(mapper -> mapper.getFactory().setStreamReadConstraints(
                StreamReadConstraints.builder()
                        .maxNestingDepth(64)
                        .maxStringLength(2_000_000)
                        .maxNumberLength(256)
                        .build()
        ));
    }
}
