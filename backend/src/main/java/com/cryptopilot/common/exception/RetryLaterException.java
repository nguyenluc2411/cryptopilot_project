package com.cryptopilot.common.exception;

import java.util.Map;

/**
 * A refusal the same request will pass after a wait: the body carries {@code retryAfter} in seconds and the handler
 * sets the {@code Retry-After} header to the same value. The seconds are also the message argument.
 *
 * <p>Rule: TECHNICAL_DESIGN 5.1; D-57.
 *
 * <p>Reference: Nottingham, M. &amp; Fielding, R. (2012). RFC 6585, section 4 (429 Too Many Requests); Fielding, R.,
 * Nottingham, M. &amp; Reschke, J. (2022). RFC 9110, section 10.2.3 (Retry-After).
 */
public class RetryLaterException extends BusinessException {

    private static final long serialVersionUID = 1L;

    /** The problem detail member holding the seconds to wait. */
    public static final String RETRY_AFTER = "retryAfter";

    private final long retryAfterSeconds;

    public RetryLaterException(ErrorCode errorCode, String detail, long retryAfterSeconds) {
        super(errorCode, detail, retryAfterSeconds);
        if (retryAfterSeconds < 1) {
            throw new IllegalArgumentException("retryAfterSeconds must be at least 1");
        }
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Whole seconds until the request may be sent again. */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    @Override
    public Map<String, Object> problemProperties() {
        return Map.of(RETRY_AFTER, retryAfterSeconds);
    }
}
