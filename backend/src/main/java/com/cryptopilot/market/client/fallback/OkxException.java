package com.cryptopilot.market.client.fallback;

/**
 * OKX could not be read (D-158): no answer, an HTTP error, an error code in the body, or a body that is not what the
 * API documents. The fallback prices are display only, so the caller shows none and changes nothing else.
 */
public class OkxException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The HTTP status OKX answered with; 0 when it did not answer or the body was unreadable. */
    private final int status;

    public OkxException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /** The HTTP status OKX answered with; 0 when it did not answer or the body was unreadable. */
    public int status() {
        return status;
    }
}
