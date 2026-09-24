package com.cryptopilot.market.client;

import java.io.Serial;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The one way a call to the exchange reports that it produced no data, and the whole of the contract a
 * caller has to honour.
 *
 * <h2>One type, six kinds</h2>
 *
 * <p>Every public method of {@link BinanceRestClient} either returns data or throws this — never another
 * exception type for an outcome of the exchange, never {@code null}, never an empty list standing for a
 * failure. {@link #kind()} says why, {@link #venue()} which API, and {@link #retryAt()} the instant from
 * which a call to that venue may succeed, wherever the client knows it:
 *
 * <table border="1">
 * <caption>What each kind means and what the caller does</caption>
 * <tr><th>Kind</th><th>Cause</th><th>{@code retryAt}</th><th>The caller</th></tr>
 * <tr><td>{@link Kind#RATE_LIMITED}</td><td>80% of the weight budget used, or a 429</td>
 *     <td>next minute, or {@code Retry-After}</td><td>skips the run, reschedules at or after {@code retryAt}</td></tr>
 * <tr><td>{@link Kind#BANNED}</td><td>a 418, possibly recorded before this start</td>
 *     <td>the end of the ban</td><td>skips the run, reschedules at or after {@code retryAt}; a person looks at the alert</td></tr>
 * <tr><td>{@link Kind#CIRCUIT_OPEN}</td><td>repeated 5xx, timeouts or refused connections</td>
 *     <td>the end of the open period</td><td>skips the run, reschedules at or after {@code retryAt}</td></tr>
 * <tr><td>{@link Kind#UNAVAILABLE}</td><td>a 5xx, timeout or refused connection that outlasted the retries</td>
 *     <td>empty</td><td>skips the run and waits for its next scheduled one</td></tr>
 * <tr><td>{@link Kind#REJECTED}</td><td>any other 4xx: the request is wrong</td>
 *     <td>empty</td><td>logs a defect; repeating the same request will fail again</td></tr>
 * <tr><td>{@link Kind#MALFORMED}</td><td>a body that is not the documented shape</td>
 *     <td>empty</td><td>logs a defect; waiting will not fix a changed format</td></tr>
 * </table>
 *
 * <h2>What a caller must never do</h2>
 *
 * <p>Never loop on a refusal and never sleep in the calling thread to wait one out. The client has already
 * retried what is worth retrying, and a refusal with a {@code retryAt} is returned at once, without a
 * request, precisely so that a scheduled job fails fast and gives its thread back: the right response is
 * to end the run and let the scheduler come back at or after {@code retryAt}. A caller that loops turns
 * the exchange's 429 into the repeat offence that becomes a 418, and a caller that sleeps holds a thread
 * hostage to a ban that may last days.
 *
 * <p>Not a {@code BusinessException}: nothing here reaches a user or maps to an SRS message. These
 * outcomes stay inside the background jobs of NSF-01 to NSF-04.
 *
 * <p>Rule: BR-09; NSF-01 to NSF-04; TECHNICAL_DESIGN 7.1.2.
 *
 * <p>Reference: Nygard, M. T. (2018). <i>Release It! Design and Deploy Production-Ready Software</i>
 * (2nd ed.). Pragmatic Bookshelf, ch. 5 ("Fail Fast": refuse at once when the outcome is already known;
 * "Circuit Breaker": callers stop calling an integration point that keeps failing).
 */
public final class BinanceClientException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Why the call produced no data. */
    public enum Kind {

        /** The request-weight budget is spent, or the exchange answered 429. Wait until {@link #retryAt()}. */
        RATE_LIMITED,

        /** The exchange answered 418: this IP is banned until {@link #retryAt()}. */
        BANNED,

        /** Too many consecutive failures; the venue is not called again until {@link #retryAt()}. */
        CIRCUIT_OPEN,

        /** A 5xx, a timeout or a connection failure that outlasted the retries. */
        UNAVAILABLE,

        /** A 4xx other than 418 and 429: the request itself is wrong and will not improve by waiting. */
        REJECTED,

        /** The body could not be read as the documented shape. */
        MALFORMED
    }

    private final Kind kind;
    private final BinanceVenue venue;
    private final transient Instant retryAt;

    BinanceClientException(Kind kind, BinanceVenue venue, String message, Instant retryAt, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.venue = Objects.requireNonNull(venue, "venue must not be null");
        this.retryAt = retryAt;
    }

    /** Why the call produced no data. */
    public Kind kind() {
        return kind;
    }

    /** Which of the two APIs refused. */
    public BinanceVenue venue() {
        return venue;
    }

    /**
     * The instant from which a call to the same venue may succeed — the exchange's {@code Retry-After},
     * the next minute of the weight budget, or the end of an open circuit. Present for
     * {@link Kind#RATE_LIMITED}, {@link Kind#BANNED} and {@link Kind#CIRCUIT_OPEN}; empty for the other
     * three, which no amount of waiting is known to cure.
     */
    public Optional<Instant> retryAt() {
        return Optional.ofNullable(retryAt);
    }
}
