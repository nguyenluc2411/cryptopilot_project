package com.cryptopilot.market.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cryptopilot.support.MutableTestClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The gate of one venue on its own: the parts of its behaviour that an HTTP round trip cannot place
 * precisely — two calls racing for the single trial of a half-open breaker, a rate limit that must not
 * shorten a longer ban, and the alert a ban raises.
 *
 * <p>Rule: BR-09; TECHNICAL_DESIGN 7.1.2.
 */
class BinanceRequestGateTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:30Z");

    private final MutableTestClock clock = new MutableTestClock(NOW);

    private final BinanceRequestGate gate =
            new BinanceRequestGate(BinanceVenue.SPOT, 6000, 80, 2, Duration.ofSeconds(30), clock);

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    private final Logger gateLogger = (Logger) LoggerFactory.getLogger(BinanceRequestGate.class);

    @BeforeEach
    void captureTheLog() {
        logs.start();
        gateLogger.addAppender(logs);
    }

    @AfterEach
    void releaseTheLog() {
        gateLogger.detachAppender(logs);
    }

    /**
     * A half-open breaker lets exactly one call through. A second call arriving while the trial is out
     * is refused, so a recovering exchange is not hit by every waiting job at once.
     */
    @Test
    void TD712_aHalfOpenBreaker_letsExactlyOneTrialThrough() {
        gate.failed();
        gate.failed();
        clock.advance(Duration.ofSeconds(30));

        assertThatCode(gate::admit).doesNotThrowAnyException();
        BinanceClientException second = refusalOf(gate::admit);

        assertThat(second.kind()).isEqualTo(BinanceClientException.Kind.CIRCUIT_OPEN);
        assertThat(second.getMessage()).contains("trial");
    }

    /** A short rate limit arriving during a longer ban does not shorten the ban. */
    @Test
    void BR09_aShorterRateLimit_neverShortensALongerBan() {
        gate.banned(Duration.ofMinutes(10));

        gate.rateLimited(Duration.ofSeconds(5));

        BinanceClientException refusal = refusalOf(gate::admit);
        assertThat(refusal.kind()).isEqualTo(BinanceClientException.Kind.BANNED);
        assertThat(refusal.retryAt()).contains(NOW.plus(Duration.ofMinutes(10)));
    }

    /** A 418 is an alert: it is logged at ERROR, because a ban needs a person and not a retry. */
    @Test
    void BR09_aBan_isLoggedAtAlertLevel() {
        gate.banned(Duration.ofMinutes(2));

        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("ALERT").contains("418");
        });
    }

    /** A success closes a breaker that was open, and a later run has to start from zero. */
    @Test
    void TD712_aSuccess_closesTheBreakerAndResetsTheRun() {
        gate.failed();
        gate.failed();
        clock.advance(Duration.ofSeconds(30));
        gate.admit();

        gate.succeeded();
        gate.failed();

        assertThatCode(gate::admit)
                .as("one failure after a success is not a run")
                .doesNotThrowAnyException();
    }

    private static BinanceClientException refusalOf(Runnable call) {
        try {
            call.run();
        } catch (BinanceClientException refusal) {
            return refusal;
        }
        throw new AssertionError("the call was expected to be refused");
    }
}
