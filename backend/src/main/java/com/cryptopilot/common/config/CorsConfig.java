package com.cryptopilot.common.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * The CORS policy of every path, REST and the STOMP handshake alike, from {@link CorsProperties}. Applied by the
 * security chain ({@code http.cors(...)}), which picks up the bean by its name, so a preflight is answered before
 * authorization and a request from an origin not on the list is refused with 403.
 *
 * <p>No credentials: the API takes bearer tokens in a header, never cookies, so a browser never needs to send them.
 *
 * <p>Rule: TECHNICAL_DESIGN 5.3.
 *
 * <p>Reference: WHATWG. <i>Fetch Standard</i>, "CORS protocol".
 */
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class CorsConfig {

    static final List<String> METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    static final List<String> REQUEST_HEADERS = List.of("Authorization", "Content-Type", "Accept", "X-Correlation-Id");

    static final List<String> EXPOSED_HEADERS = List.of("X-Correlation-Id", "Retry-After");

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration policy = new CorsConfiguration();
        // Patterns, so that the local-development "http://localhost:*" works; production entries are exact.
        policy.setAllowedOriginPatterns(properties.allowedOrigins());
        policy.setAllowedMethods(METHODS);
        policy.setAllowedHeaders(REQUEST_HEADERS);
        policy.setExposedHeaders(EXPOSED_HEADERS);
        policy.setAllowCredentials(false);
        policy.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", policy);
        return source;
    }
}
