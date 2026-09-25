package com.cryptopilot.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock a test moves by hand.
 *
 * <p>The application reads every instant from an injected {@link Clock}, which is what makes an
 * expiry testable without sleeping: a test replaces this one bean, puts the clock exactly one
 * millisecond either side of a boundary, and asserts what the rule does there. Sleeping would test
 * the same boundary far less precisely and would cost a second every time.
 *
 * <p>Always UTC, like the bean it replaces (BR-08).
 */
public final class MutableTestClock extends Clock {

    private volatile Instant instant;

    public MutableTestClock(Instant start) {
        this.instant = start;
    }

    /** Moves the clock forward, or backward with a negative amount. */
    public void advance(Duration amount) {
        instant = instant.plus(amount);
    }

    /** Puts the clock at an instant, whatever it read before. */
    public void set(Instant now) {
        instant = now;
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("a test clock is read in UTC only");
    }
}
