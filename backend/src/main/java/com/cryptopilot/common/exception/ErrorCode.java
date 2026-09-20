package com.cryptopilot.common.exception;

import org.springframework.http.HttpStatus;

/**
 * The catalogue of error codes the API can return. Each constant ties three things together that
 * must never drift apart: the stable machine-readable code a client branches on, the HTTP status
 * the response carries, and the SRS message code that tells the client which text of SRS section
 * 5.3 to display.
 *
 * <p>The name of the constant is the stable code. It is part of the published API, so a constant is
 * renamed only with a breaking API change; adding a constant is not breaking.
 *
 * <p>Only the codes the shared kernel itself raises live here so far. Each business module adds its
 * own constants as its use cases are built, and {@code ErrorCodeTest} keeps the invariant that every
 * constant names exactly one existing {@code MSGxx} id.
 *
 * <p>Rule: TECHNICAL_DESIGN section 5.1; SRS section 5.3 (application messages MSG01…MSG45).
 *
 * <p>Reference: Nottingham, M., Wilde, E. &amp; Dalal, S. (2023). RFC 9457, <i>Problem Details for
 * HTTP APIs</i>, section 3 (a problem type is a stable identifier, separate from the status code).
 */
public enum ErrorCode {

    /**
     * The request failed validation. The offending fields are reported one by one in the
     * {@code errors} property of the response; MSG01 is the generic "the request is not valid"
     * text, while the per-field message carries the specific rule (MSG02, MSG03, MSG15, MSG16 …).
     */
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "MSG01"),

    /** The addressed resource does not exist, or is not visible to the caller. */
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "MSG41"),

    /**
     * Nothing in the catalogue matched: an unhandled exception reached the boundary. The response
     * carries a reference code and no technical detail (SRS 4.2.4).
     */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "MSG43");

    private final HttpStatus status;
    private final String messageCode;

    ErrorCode(HttpStatus status, String messageCode) {
        this.status = status;
        this.messageCode = messageCode;
    }

    /** The stable code returned as the {@code code} property of the problem detail. */
    public String code() {
        return name();
    }

    /** The HTTP status of a response carrying this error. */
    public HttpStatus status() {
        return status;
    }

    /** The SRS message id (MSGxx) whose text the client displays for this error. */
    public String messageCode() {
        return messageCode;
    }
}
