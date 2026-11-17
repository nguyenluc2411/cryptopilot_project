package com.cryptopilot.auth.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The BR-65 wait as a pure calculation, at and around both limits. */
class VerificationResendLimitTest {

    private static final Instant T0 = Instant.parse("2026-11-17T08:00:00Z");
    private static final Duration INTERVAL = Duration.ofSeconds(60);
    private static final Duration WINDOW = Duration.ofHours(24);

    @Test
    void BR65_noResendYet_waitsNothing() {
        assertThat(wait(List.of(), T0, 5)).isZero();
    }

    @Test
    void BR65_theInterval_endsExactlyAt60Seconds() {
        List<Instant> one = List.of(T0);

        assertThat(wait(one, T0.plusSeconds(59), 5)).isEqualTo(Duration.ofSeconds(1));
        assertThat(wait(one, T0.plusSeconds(60), 5)).isZero();
    }

    @Test
    void BR65_theCap_waitsForTheOldestToLeaveTheWindow() {
        List<Instant> five =
                List.of(T0, T0.plusSeconds(60), T0.plusSeconds(120), T0.plusSeconds(180), T0.plusSeconds(240));

        assertThat(wait(five.subList(0, 4), T0.plusSeconds(300), 5))
                .as("the fifth")
                .isZero();
        assertThat(wait(five, T0.plusSeconds(300), 5)).as("the sixth").isEqualTo(WINDOW.minusSeconds(300));
    }

    /** A cap lowered below the rows already in the window frees a place only when enough of them have left it. */
    @Test
    void BR65_aCapLoweredBelowTheCount_waitsForEnoughToLeave() {
        List<Instant> four = List.of(T0, T0.plusSeconds(60), T0.plusSeconds(120), T0.plusSeconds(180));

        assertThat(wait(four, T0.plusSeconds(3600), 2))
                .as("three of the four must leave: the third frees the place")
                .isEqualTo(Duration.between(
                        T0.plusSeconds(3600), T0.plusSeconds(120).plus(WINDOW)));
    }

    @Test
    void BR65_theSeconds_areRoundedUp() {
        assertThat(VerificationResendLimit.secondsRoundedUp(Duration.ofMillis(1)))
                .isEqualTo(1L);
        assertThat(VerificationResendLimit.secondsRoundedUp(Duration.ofSeconds(60)))
                .isEqualTo(60L);
        assertThat(VerificationResendLimit.secondsRoundedUp(Duration.ofMillis(60_001)))
                .isEqualTo(61L);
    }

    @Test
    void BR65_theAddress_isTrimmedAndLowerCased() {
        assertThat(VerificationResendLimit.normalised("  A@X.com ")).isEqualTo("a@x.com");
    }

    private static Duration wait(List<Instant> recent, Instant now, int max) {
        return VerificationResendLimit.waitBeforeNext(recent, now, INTERVAL, max, WINDOW);
    }
}
