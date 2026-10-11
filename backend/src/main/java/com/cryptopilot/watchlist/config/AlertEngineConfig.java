package com.cryptopilot.watchlist.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Binds the alert engine's settings.
 *
 * <p>Rule: NSF-06; TECHNICAL_DESIGN 7.9.
 */
@Configuration
@EnableConfigurationProperties(AlertEngineProperties.class)
public class AlertEngineConfig {

    /**
     * Refuses to start when {@code tombstoneTtl} is not longer than the longest read of an alert: a book would then
     * forget a removal while a read from before it may still arrive.
     */
    @Bean
    AlertReadBound alertReadBound(AlertEngineProperties properties, Environment environment) {
        AlertReadBound bound = AlertReadBound.of(environment);
        if (properties.tombstoneTtl().compareTo(bound.longest()) <= 0) {
            throw new IllegalStateException("cryptopilot.alert.tombstone-ttl (" + properties.tombstoneTtl()
                    + ") must be longer than the longest read of an alert: " + AlertReadBound.CONNECTION_TIMEOUT
                    + " (" + bound.connectionTimeout() + ") plus " + AlertReadBound.QUERY_TIMEOUT + " ("
                    + bound.queryTimeout() + ")");
        }
        return bound;
    }
}
