package com.cryptopilot.community.client;

/**
 * The media storage could not be reached, timed out, refused the credentials or answered with an error. Its message
 * never carries the account secret or a signature.
 */
public class MediaStorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MediaStorageException(String message) {
        super(message);
    }

    public MediaStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
