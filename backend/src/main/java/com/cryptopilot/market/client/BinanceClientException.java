package com.cryptopilot.market.client;

import java.io.Serial;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A call to the exchange that did not produce data, and why.
 *
 * <p>The kind tells the caller what to do next, which is the only reason to distinguish them: a
 * {@link Kind#RATE_LIMITED}, {@link Kind#BANNED} or {@link Kind#CIRCUIT_OPEN} refusal carries the instant
 * after which a call may succeed, and the job that asked waits until then; a {@link Kind#REJECTED} or
 * {@link Kind#MALFORMED} one will fail the same way again and is a defect to log, not a condition to
 * wait out; {@link Kind#UNAVAILABLE} is an outage that outlasted the retries.
 *
 * <p>Not a {@code BusinessException}: nothing here is shown to a user or maps to an SRS message. These
 * failures stay inside the background jobs of NSF-01 to NSF-04.
 *
 * <p>Rule: BR-09; NSF-01 to NSF-04; TECHNICAL_DESIGN 7.1.2.
 */
public class BinanceClientException extends RuntimeException {

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

    /** When a call to the same venue may succeed, for the kinds that are waited out; empty otherwise. */
    public Optional<Instant> retryAt() {
        return Optional.ofNullable(retryAt);
    }
}
