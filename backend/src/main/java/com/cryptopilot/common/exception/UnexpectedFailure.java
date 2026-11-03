package com.cryptopilot.common.exception;

import java.util.Objects;

/**
 * Published when a request ends in an unexpected error (HTTP 500), for the system log of UC-46 to record. The shared
 * kernel knows nothing of who listens: {@code admin} does, and the handler answers the same whether anyone does or
 * not.
 *
 * @param operation the request's method and path, such as {@code GET /api/v1/plans}, or {@code unknown}
 * @param error what was thrown
 * @param reference the reference the caller was answered with
 *     <p>Rule: UC-46; TECHNICAL_DESIGN section 5.1.
 */
public record UnexpectedFailure(String operation, Throwable error, String reference) {

    public UnexpectedFailure {
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(error, "error must not be null");
    }
}
