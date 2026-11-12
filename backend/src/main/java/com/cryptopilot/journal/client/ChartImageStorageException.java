package com.cryptopilot.journal.client;

/**
 * The chart image storage could not be reached or answered with an error.
 *
 * <p>Rule: BR-40; D-98.
 */
public class ChartImageStorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ChartImageStorageException(String message) {
        super(message);
    }

    public ChartImageStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
