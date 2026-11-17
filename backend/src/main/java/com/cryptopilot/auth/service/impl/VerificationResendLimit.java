package com.cryptopilot.auth.service.impl;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * The resend limit of BR-65 as a pure calculation: how long an address must wait before its next verification mail.
 *
 * <p>Rule: BR-65; UC-02; D-57 (rolling 24 hours, 60 s cooldown, per address), A-36 (2).
 *
 * <p>Reference: OWASP ASVS 4.0.3, V2.2.1 (anti-automation controls on authentication endpoints); Fielding, R.,
 * Nottingham, M. &amp; Reschke, J. (2022). RFC 9110: HTTP Semantics, section 10.2.3 (Retry-After).
 */
final class VerificationResendLimit {

    private VerificationResendLimit() {}

    /** Trimmed and lower-cased, as the account lookup ignores case; the counter key is the hash of this. */
    static String normalised(String address) {
        return address.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * The wait before the next resend is accepted, zero when it may go now.
     *
     * @param recent the accepted resends inside the window ending at {@code now}, oldest first
     */
    static Duration waitBeforeNext(
            List<Instant> recent, Instant now, Duration interval, int maxInWindow, Duration window) {
        if (recent.isEmpty()) {
            return Duration.ZERO;
        }
        Instant next = recent.getLast().plus(interval);
        if (recent.size() >= maxInWindow) {
            // The window frees a place once enough of the oldest resends have left it.
            Instant freed = recent.get(recent.size() - maxInWindow).plus(window);
            next = freed.isAfter(next) ? freed : next;
        }
        return next.isAfter(now) ? Duration.between(now, next) : Duration.ZERO;
    }

    /** Whole seconds, rounded up, so a client that waits this long is never refused again for the same reason. */
    static long secondsRoundedUp(Duration wait) {
        long seconds = wait.getSeconds();
        return wait.getNano() > 0 ? seconds + 1 : seconds;
    }
}
