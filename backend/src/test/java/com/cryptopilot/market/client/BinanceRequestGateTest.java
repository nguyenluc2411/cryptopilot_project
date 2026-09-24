package com.cryptopilot.market.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cryptopilot.support.MutableTestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
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

    private final BinanceRequestGate gate = new BinanceRequestGate(
            BinanceVenue.SPOT, 6000, 80, 2, Duration.ofSeconds(30), clock, new InMemoryBinanceBans());

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
        gate.banned(Duration.ofMinutes(10), "test");

        gate.rateLimited(Duration.ofSeconds(5));

        BinanceClientException refusal = refusalOf(gate::admit);
        assertThat(refusal.kind()).isEqualTo(BinanceClientException.Kind.BANNED);
        assertThat(refusal.retryAt()).contains(NOW.plus(Duration.ofMinutes(10)));
    }

    /** A 418 is an alert: it is logged at ERROR, because a ban needs a person and not a retry. */
    @Test
    void BR09_aBan_isLoggedAtAlertLevel() {
        gate.banned(Duration.ofMinutes(2), "test");

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

    /**
     * A ban that cannot be written to the store still holds in memory — the refusal is never lost to a
     * database failure — and the failure is raised as an alert beside the ban itself.
     */
    @Test
    void BR09_aBanTheStoreCannotRecord_stillHoldsAndIsAlerted() {
        BinanceRequestGate failingStore = new BinanceRequestGate(
                BinanceVenue.SPOT, 6000, 80, 2, Duration.ofSeconds(30), clock, new BinanceBanStore() {
                    @Override
                    public Optional<Instant> bannedUntil(BinanceVenue venue) {
                        return Optional.empty();
                    }

                    @Override
                    public void recordBan(BinanceVenue venue, Instant bannedUntil, String reason) {
                        throw new IllegalStateException("database down");
                    }
                });

        failingStore.banned(Duration.ofMinutes(2), "test");

        assertThat(refusalOf(failingStore::admit).kind()).isEqualTo(BinanceClientException.Kind.BANNED);
        assertThat(logs.list)
                .anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("could not be recorded"));
    }

    /**
     * A store that cannot be read fails the call, and is asked again next time: the first call after a
     * start must not go out blind to a ban recorded before it.
     */
    @Test
    void BR09_aStoreThatCannotBeRead_isAskedAgainOnTheNextCall() {
        int[] reads = {0};
        BinanceRequestGate flakyStore = new BinanceRequestGate(
                BinanceVenue.SPOT, 6000, 80, 2, Duration.ofSeconds(30), clock, new BinanceBanStore() {
                    @Override
                    public Optional<Instant> bannedUntil(BinanceVenue venue) {
                        if (reads[0]++ == 0) {
                            throw new IllegalStateException("database down");
                        }
                        return Optional.of(NOW.plusSeconds(60));
                    }

                    @Override
                    public void recordBan(BinanceVenue venue, Instant bannedUntil, String reason) {}
                });

        assertThatThrownBy(flakyStore::admit).isInstanceOf(IllegalStateException.class);

        assertThat(refusalOf(flakyStore::admit).kind()).isEqualTo(BinanceClientException.Kind.BANNED);
        assertThatCode(() -> refusalOf(flakyStore::admit)).doesNotThrowAnyException();
        assertThat(reads[0]).as("read until it succeeded, then never again").isEqualTo(2);
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
