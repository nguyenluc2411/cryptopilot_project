package com.cryptopilot.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

/**
 * The start-up warning for production limiting requests per client address while trusting no proxy.
 *
 * <p>Rule: NSF-18; TECHNICAL_DESIGN 5.3; ADR-014.
 */
class TrustedProxyStartupCheckTest {

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(TrustedProxyStartupCheck.class);

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    @Test
    void TD53_production_withLimitsOnAndNoTrustedProxy_warnsOnceAndNamesTheVariable() {
        check(true, List.of(), "prod").warnWhenNoProxyIsTrusted();

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("TRUSTED_PROXIES").contains("10 sign-ins");
        });
    }

    @Test
    void TD53_production_withATrustedProxy_doesNotWarn() {
        check(true, List.of("10.0.0.0/8"), "prod").warnWhenNoProxyIsTrusted();

        assertThat(logs.list).isEmpty();
    }

    @Test
    void TD53_production_withLimitsOff_doesNotWarn() {
        check(false, List.of(), "prod").warnWhenNoProxyIsTrusted();

        assertThat(logs.list).isEmpty();
    }

    @Test
    void TD53_development_withNoTrustedProxy_doesNotWarn() {
        check(true, List.of(), "dev").warnWhenNoProxyIsTrusted();

        assertThat(logs.list).isEmpty();
    }

    private static TrustedProxyStartupCheck check(boolean limitsOn, List<String> proxies, String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return new TrustedProxyStartupCheck(
                new TrustedProxyProperties(proxies),
                new RateLimitProperties(limitsOn, Duration.ofMinutes(1), 10, 20, 120),
                environment);
    }
}
