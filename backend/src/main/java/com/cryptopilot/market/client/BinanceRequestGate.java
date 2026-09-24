package com.cryptopilot.market.client;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides whether a call to one venue may go out now, from what that venue last said about this IP.
 *
 * <p>Three things close the gate, each until an instant:
 *
 * <ol>
 *   <li><b>The weight budget.</b> Every response reports the weight this IP has used in the current
 *       minute. At the configured share of the budget the gate closes until the next minute begins, so
 *       the client stops before the exchange has to say 429 (TECHNICAL_DESIGN 7.1 step 5).
 *   <li><b>A 429 or a 418.</b> The exchange's own {@code Retry-After} decides how long. A 418 is a ban
 *       for having ignored earlier 429s, so it is logged at ERROR: it needs a person, not a retry.
 *   <li><b>The circuit breaker.</b> A run of calls that failed on the exchange's side — 5xx, timeouts,
 *       refused connections, each after its retries — opens the gate for a fixed time; then one call
 *       is let through, and its outcome closes the breaker or opens it again.
 * </ol>
 *
 * <p>One gate per venue, because Spot and futures are separate hosts with separate budgets. Methods are
 * {@code synchronized}: the state is a handful of fields read and written together, and a lock around
 * them costs nothing next to an HTTP call.
 *
 * <p>Rule: BR-09; NSF-01 to NSF-04; TECHNICAL_DESIGN 7.1.1 and 7.1.2.
 *
 * <p>Reference: Nygard, M. T. (2018). <i>Release It! Design and Deploy Production-Ready Software</i>
 * (2nd ed.). Pragmatic Bookshelf, ch. 5 ("Circuit Breaker": stop calling an integration point that keeps
 * failing, and let a single trial call decide when to resume; "Timeouts").
 */
final class BinanceRequestGate {

    private static final Logger log = LoggerFactory.getLogger(BinanceRequestGate.class);

    private final BinanceVenue venue;
    private final int weightPauseThreshold;
    private final int failureThreshold;
    private final Duration openDuration;
    private final Clock clock;

    private Instant closedUntil = Instant.MIN;
    private BinanceClientException.Kind closedFor;
    private int consecutiveFailures;
    private boolean trialInFlight;

    BinanceRequestGate(
            BinanceVenue venue,
            int requestWeightPerMinute,
            int weightPausePercent,
            int failureThreshold,
            Duration openDuration,
            Clock clock) {
        this.venue = venue;
        this.weightPauseThreshold = Math.max(1, requestWeightPerMinute * weightPausePercent / 100);
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
        this.clock = clock;
    }

    /**
     * Lets a call through, or refuses it with the reason and the instant it may be tried again. Once a
     * breaker's open time has passed, exactly one call is let through until its outcome is known.
     */
    synchronized void admit() {
        Instant now = clock.instant();
        if (now.isBefore(closedUntil)) {
            throw refusal(closedFor, "closed until " + closedUntil, closedUntil);
        }
        if (closedFor == BinanceClientException.Kind.CIRCUIT_OPEN) {
            if (trialInFlight) {
                throw refusal(closedFor, "a trial call is in flight", now.plus(openDuration));
            }
            trialInFlight = true;
        }
    }

    /** Records the weight a response reported; at the threshold, closes the gate for the rest of the minute. */
    synchronized void recordUsedWeight(int usedWeight) {
        if (usedWeight >= weightPauseThreshold) {
            Instant nextMinute = clock.instant().truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofMinutes(1));
            log.warn(
                    "{} used weight {} reached the pause threshold {}; pausing until {}",
                    venue,
                    usedWeight,
                    weightPauseThreshold,
                    nextMinute);
            closeUntil(nextMinute, BinanceClientException.Kind.RATE_LIMITED);
        }
    }

    /** A 429: closed for the exchange's {@code Retry-After}. */
    synchronized Instant rateLimited(Duration retryAfter) {
        Instant until = clock.instant().plus(retryAfter);
        log.warn("{} answered 429; no call until {}", venue, until);
        closeUntil(until, BinanceClientException.Kind.RATE_LIMITED);
        return until;
    }

    /** A 418: this IP is banned. Logged at ERROR, the alert level, because it needs a person. */
    synchronized Instant banned(Duration retryAfter) {
        Instant until = clock.instant().plus(retryAfter);
        log.error("ALERT {} answered 418: this IP is banned until {}; every call is stopped until then", venue, until);
        closeUntil(until, BinanceClientException.Kind.BANNED);
        return until;
    }

    /** A call that got an answer from the exchange: the breaker closes and the failure run ends. */
    synchronized void succeeded() {
        consecutiveFailures = 0;
        trialInFlight = false;
        if (closedFor == BinanceClientException.Kind.CIRCUIT_OPEN) {
            closedFor = null;
            log.info("{} answered again; circuit closed", venue);
        }
    }

    /** A call that failed on the exchange's side after its retries; enough of them in a row open the breaker. */
    synchronized void failed() {
        consecutiveFailures++;
        boolean trialFailed = trialInFlight;
        trialInFlight = false;
        if (trialFailed || consecutiveFailures >= failureThreshold) {
            Instant until = clock.instant().plus(openDuration);
            log.warn("{} failed {} calls in a row; circuit open until {}", venue, consecutiveFailures, until);
            closeUntil(until, BinanceClientException.Kind.CIRCUIT_OPEN);
        }
    }

    private void closeUntil(Instant until, BinanceClientException.Kind kind) {
        if (until.isAfter(closedUntil)) {
            closedUntil = until;
            closedFor = kind;
        }
    }

    private BinanceClientException refusal(BinanceClientException.Kind kind, String reason, Instant retryAt) {
        return new BinanceClientException(kind, venue, venue + " not called: " + reason, retryAt, null);
    }
}
