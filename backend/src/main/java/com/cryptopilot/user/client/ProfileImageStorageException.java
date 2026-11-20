package com.cryptopilot.user.client;

/**
 * The profile image storage could not be reached or answered with an error.
 *
 * <p>Rule: D-98, D-99.
 */
public class ProfileImageStorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProfileImageStorageException(String message) {
        super(message);
    }

    public ProfileImageStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
