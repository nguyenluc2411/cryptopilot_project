package com.cryptopilot.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the one clock the application reads the time from.
 *
 * <p>Nothing calls {@code Instant.now()} directly. Every component that needs the current time
 * injects this bean, which lets a test freeze time and assert an expiry, a cooldown or a candle
 * boundary exactly instead of sleeping and hoping.
 *
 * <p>The zone is UTC rather than the zone of the machine: candle boundaries, funding times and
 * token expiries are all defined in UTC, and the conversion to UTC+7 happens in the clients only.
 *
 * <p>Rule: BR-08; TECHNICAL_DESIGN section 5.2.
 *
 * <p>Reference: Freeman, S. and Pryce, N. (2009). <i>Growing Object-Oriented Software, Guided by
 * Tests</i>. Addison-Wesley, ch. 6 (make an awkward dependency such as the system clock an explicit
 * collaborator).
 */
@Configuration
public class ClockConfig {

    /** The application-wide UTC clock. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
