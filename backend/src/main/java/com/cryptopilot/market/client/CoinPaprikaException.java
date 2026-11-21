package com.cryptopilot.market.client;

import com.cryptopilot.common.exception.ExternalAnswer;

/**
 * A call to CoinPaprika that did not give what was asked: the service refused it (a 4xx, a 429 among them), failed,
 * did not answer in time, or answered something unreadable.
 *
 * <p>Rule: UC-52 (a 4xx is the service answering, not an outage).
 */
public class CoinPaprikaException extends RuntimeException implements ExternalAnswer {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final boolean notSent;

    public CoinPaprikaException(String message, int status, Throwable cause) {
        this(message, status, cause, false);
    }

    /** {@code notSent} when the client refused the call itself (its budget, or a wait CoinPaprika asked for). */
    public CoinPaprikaException(String message, int status, Throwable cause, boolean notSent) {
        super(message, cause);
        this.status = status;
        this.notSent = notSent;
    }

    /** The HTTP status, or 0 when there was no answer or it was unreadable. */
    public int status() {
        return status;
    }

    /**
     * Whether CoinPaprika asked to slow down: a 429, or the 402 its free plan answers once the hourly or monthly calls
     * are spent.
     */
    public boolean rateLimited() {
        return status == 429 || status == 402;
    }

    @Override
    public boolean notSent() {
        return notSent;
    }

    @Override
    public boolean serviceAnswered() {
        return status >= 400 && status < 500;
    }
}
