package com.cryptopilot.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the application clock with one that stands still, for tests that write data relative to "now" and read it
 * back through the API. With the real clock, a run that crosses a minute or an hour boundary between writing and
 * reading sees the data on the other side of it.
 *
 * <p>Rule: TECHNICAL_DESIGN 5.2 (every component reads the injected clock, so a test can fix it).
 */
@TestConfiguration(proxyBeanMethods = false)
public class FixedClockConfig {

    /** Inside a minute and an hour, so truncating it to either never lands on the instant itself. */
    public static final Instant NOW = Instant.parse("2026-09-29T10:30:15Z");

    @Bean
    @Primary
    Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
