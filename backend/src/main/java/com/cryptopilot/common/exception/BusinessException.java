package com.cryptopilot.common.exception;

import java.util.Objects;

/**
 * Thrown when a request is rejected by a business rule rather than by a technical failure. It
 * carries the {@link ErrorCode} that decides the HTTP status and the SRS message code of the
 * response, so a use case states <em>what</em> was refused and the web layer alone decides how that
 * is rendered.
 *
 * <p>Unchecked on purpose: a business rejection is not something a caller can recover from
 * locally — it travels to {@link GlobalExceptionHandler}, which turns it into a problem detail.
 *
 * <p>The detail text is a plain English sentence for developers and logs. The text shown to a user
 * is chosen by the client from {@link ErrorCode#messageCode()}, so no message of SRS section 5.3 is
 * duplicated in the backend.
 *
 * <p>Rule: TECHNICAL_DESIGN section 5.1.
 *
 * <p>Reference: Bloch, J. (2018). <i>Effective Java</i> (3rd ed.). Addison-Wesley, Item 70
 * (unchecked exceptions for conditions the caller cannot usefully recover from) and Item 75 (a
 * detail message states the values that failed the check).
 */
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    /**
     * @param errorCode the catalogue entry describing the rejection, never {@code null}
     * @param detail a non-blank sentence naming what was refused and why
     */
    public BusinessException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, null);
    }

    /**
     * @param errorCode the catalogue entry describing the rejection, never {@code null}
     * @param detail a non-blank sentence naming what was refused and why
     * @param cause the technical failure behind the rejection, or {@code null}
     */
    public BusinessException(ErrorCode errorCode, String detail, Throwable cause) {
        super(requireText(detail), cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    /** The catalogue entry that decides the status and the SRS message code of the response. */
    public ErrorCode errorCode() {
        return errorCode;
    }

    private static String requireText(String detail) {
        if (detail == null || detail.isBlank()) {
            throw new IllegalArgumentException("detail must not be blank");
        }
        return detail;
    }
}
