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
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "MSG43"),

    /**
     * The account is LOCKED or BANNED, so it cannot sign in (BR-06). 403 rather than 401: the
     * credentials were not the problem, and MSG10 tells the holder which of the two states applies.
     */
    ACCOUNT_NOT_ACTIVE(HttpStatus.FORBIDDEN, "MSG10"),

    /**
     * The account has not verified its address, so it cannot sign in yet (BR-01). MSG11 carries the
     * offer to send the verification mail again.
     */
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "MSG11"),

    /**
     * A verification was applied to an account that is already verified. From the holder's side
     * this is a link that has been used once already, which is what MSG07 says.
     */
    EMAIL_ALREADY_VERIFIED(HttpStatus.CONFLICT, "MSG07"),

    /**
     * An administrator's status change does not exist in the account lifecycle — unlocking an
     * account that is not locked, or any action on a banned one (BR-05, BR-06).
     *
     * <p>SRS section 5.3 has no error text for this. MSG39 is the only message whose context is an
     * administrator changing an account status, and it is written as a confirmation prompt rather
     * than a refusal, so it is named here as the nearest existing message while the alignment item
     * that asks for a proper one is open.
     */
    ACCOUNT_STATUS_TRANSITION_INVALID(HttpStatus.CONFLICT, "MSG39"),

    /**
     * A single-use token was presented after it had been used or after it expired (BR-01, BR-04).
     * MSG07 is the text for both, deliberately: telling the two apart would say whether the link
     * ever existed.
     */
    TOKEN_INVALID_OR_EXPIRED(HttpStatus.BAD_REQUEST, "MSG07"),

    /**
     * The address is already registered (SRS UC-01). A conflict rather than a validation error,
     * because nothing about the request is malformed: it asks for something that already exists.
     *
     * <p>The SRS chooses to say so. MSG04 tells a visitor that the address is taken, which does
     * reveal that an account holds it; the password reset flow of BR-04 is the one that must not
     * (MSG12), and the two are deliberately different because a registration form that refused to
     * say would instead fail at the second step and confuse everyone who mistyped.
     */
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "MSG04"),

    /**
     * The password does not satisfy BR-02: 8 to 64 characters, with at least one upper-case letter,
     * one lower-case letter and one digit.
     */
    PASSWORD_POLICY_VIOLATION(HttpStatus.BAD_REQUEST, "MSG03"),

    /**
     * A write lost a race with another one: a unique constraint refused the row because an equal
     * one was committed first. 409 rather than 500, because nothing is broken — the same request
     * sent again will be answered properly, by the rule that owns the constraint.
     *
     * <p>This is the safety net, not the answer a user should normally see. A use case that knows
     * which constraint it can collide with catches the violation and raises its own code with the
     * message SRS 5.3 assigns it; registration will raise MSG04 for a duplicate address. What is
     * left for this code is the collision nobody anticipated, and SRS 5.3 has no text for that, so
     * it borrows the reference-code message of MSG43 while the alignment item asking for one is
     * open.
     */
    DATA_CONFLICT(HttpStatus.CONFLICT, "MSG43");

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
