package com.cryptopilot.market.client;

import com.cryptopilot.common.exception.ExternalAnswer;

/**
 * A call for the Fear &amp; Greed Index that did not give a reading: refused (a 4xx), failed, not answered in time, or
 * answered something unreadable.
 *
 * <p>Rule: UC-52 (a 4xx is the service answering, not an outage).
 */
public class FearGreedException extends RuntimeException implements ExternalAnswer {

    private static final long serialVersionUID = 1L;

    private final int status;

    public FearGreedException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /** The HTTP status, or 0 when there was no answer or it was unreadable. */
    public int status() {
        return status;
    }

    @Override
    public boolean serviceAnswered() {
        return status >= 400 && status < 500;
    }
}
