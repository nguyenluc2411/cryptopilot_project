package com.cryptopilot.notification.client;

import com.cryptopilot.notification.model.enums.PushOutcome;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * What one send answered: the outcome and, for a retryable failure, how long the service asked to wait before the next
 * attempt ({@code Retry-After}), if it said so (T-058, NSF-15).
 *
 * <p>Reference: Fielding, R. et al. (2022). <i>RFC 9110: HTTP Semantics</i>, §10.2.3 "Retry-After".
 *
 * @param outcome what the service answered
 * @param retryAfter the wait the service asked for, or {@code null} when it named none
 */
public record PushResult(PushOutcome outcome, Duration retryAfter) {

    public PushResult {
        Objects.requireNonNull(outcome, "outcome");
    }

    public static PushResult of(PushOutcome outcome) {
        return new PushResult(outcome, null);
    }

    public Optional<Duration> retryAfterHint() {
        return Optional.ofNullable(retryAfter);
    }
}
