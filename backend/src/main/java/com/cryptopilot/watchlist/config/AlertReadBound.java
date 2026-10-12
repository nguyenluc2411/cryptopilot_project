package com.cryptopilot.watchlist.config;

import java.time.Duration;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * The longest a read of an alert may take as the datasource is configured: the wait for a pooled connection plus the
 * statement's query timeout, when one is set. A removal must be remembered for longer than this (NSF-06).
 *
 * <p>Rule: NSF-06; TECHNICAL_DESIGN 7.9.
 *
 * @param connectionTimeout how long a read may wait for a connection ({@code spring.datasource.hikari.connection-timeout})
 * @param queryTimeout how long its statement may run ({@code jakarta.persistence.query.timeout}); zero when not set
 */
public record AlertReadBound(Duration connectionTimeout, Duration queryTimeout) {

    static final String CONNECTION_TIMEOUT = "spring.datasource.hikari.connection-timeout";
    static final String QUERY_TIMEOUT = "spring.jpa.properties.jakarta.persistence.query.timeout";

    /** Hikari's default, also used when it is set below Hikari's minimum of 250 ms. */
    private static final long HIKARI_DEFAULT_MILLIS = 30_000;

    private static final long HIKARI_MIN_MILLIS = 250;

    /** Reads the bound from the settings; both are in milliseconds, and a connection timeout of 0 means no limit. */
    static AlertReadBound of(Environment environment) {
        Binder binder = Binder.get(environment);
        long connection = binder.bind(CONNECTION_TIMEOUT, Long.class).orElse(HIKARI_DEFAULT_MILLIS);
        long query = binder.bind(QUERY_TIMEOUT, Long.class).orElse(0L);
        Duration wait = connection == 0
                ? Duration.ofMillis(Long.MAX_VALUE)
                : Duration.ofMillis(connection < HIKARI_MIN_MILLIS ? HIKARI_DEFAULT_MILLIS : connection);
        return new AlertReadBound(wait, Duration.ofMillis(Math.max(0, query)));
    }

    /** The connection wait and the query timeout together. */
    public Duration longest() {
        return connectionTimeout.plus(queryTimeout);
    }
}
