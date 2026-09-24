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
     * The address or the password was wrong (SRS 3.2.3). One code for both, because MSG08 is one
     * sentence for both: saying which of the two was wrong turns the endpoint into a way of finding
     * out which addresses hold accounts.
     *
     * <p>401 rather than 400: the request was well formed and the credentials it carried were
     * refused, which is exactly what the status means.
     */
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "MSG08"),

    /**
     * Five consecutive failures have locked the account for fifteen minutes (BR-03). MSG09 carries
     * the minutes still to wait as its one message argument.
     *
     * <p>429 rather than 401 or 403: nothing is wrong with the credentials this request carried - the
     * account is refusing attempts for a while because too many arrived, which is the one situation
     * 429 describes. It also tells an automated client the right thing, where 401 would invite it to
     * try again immediately.
     */
    LOGIN_TEMPORARILY_LOCKED(HttpStatus.TOO_MANY_REQUESTS, "MSG09"),

    /**
     * The account is LOCKED or BANNED, so it cannot sign in (BR-06). 403 rather than 401: the
     * credentials were not the problem, and MSG10 tells the holder which of the two states applies -
     * as its one message argument, because the sentence names the state.
     */
    ACCOUNT_NOT_ACTIVE(HttpStatus.FORBIDDEN, "MSG10"),

    /**
     * The refresh token presented was unknown, already used, expired, or belongs to a family that has
     * been revoked (SRS 3.2.3, TECHNICAL_DESIGN 7.15). One code and one message for all four, and
     * deliberately: from the holder's side the session is over either way, and telling a caller which
     * of the four applies tells whoever is holding a copied token how far they got.
     *
     * <p>MSG44 is the text SRS 3.2.3 assigns an expired session.
     */
    SESSION_EXPIRED(HttpStatus.UNAUTHORIZED, "MSG44"),

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
    DATA_CONFLICT(HttpStatus.CONFLICT, "MSG43"),

    /**
     * The current password offered on the Security tab is wrong (SRS 3.2.5, UC-07). MSG08 is the
     * message SRS 3.2.5 assigns it.
     *
     * <p>A code of its own rather than {@link #INVALID_CREDENTIALS}, because the status must differ:
     * that one is 401, and a 401 on a request that carried a valid access token tells a client its
     * session is over, which would send somebody who mistyped their password back to the sign-in
     * screen. This caller is authenticated; what was wrong was a field of the form, so 400.
     */
    CURRENT_PASSWORD_INCORRECT(HttpStatus.BAD_REQUEST, "MSG08"),

    /**
     * The request carried no usable credential, so the caller is not authenticated (SRS 4.2.4).
     *
     * <p>Raised by the filter chain rather than by a use case: a missing, malformed, expired or
     * wrongly signed bearer token all end here, and deliberately as one code. Which of the four
     * applies is of interest only to whoever is holding the token, and the client's action is the
     * same in every case - obtain a new session, or send the holder to the sign-in screen.
     *
     * <p>MSG44 is the text SRS 3.2.3 assigns an ended session, and it is the right thing to show:
     * from the holder's side that is exactly what has happened. It is shared with
     * {@link #SESSION_EXPIRED}, which is the same fact discovered one layer lower - a refresh token
     * that no longer renews anything. The codes stay separate because they are raised by different
     * things and a client may want to tell them apart; the text does not, because the person reading
     * it would not.
     */
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "MSG44"),

    /**
     * The caller is authenticated and the role they hold does not reach this endpoint (SRS 3.1.3,
     * SRS 4.2.4).
     *
     * <p>403 rather than 401, and the distinction is the whole point: 401 says the credential was
     * missing or bad and inviting the caller to present another one is sensible, while 403 says the
     * credential was fine and presenting it again will not help.
     *
     * <p>The response names no role and no requirement. A Trader who probes an administration
     * endpoint learns that it exists and refuses them, which is unavoidable, and nothing further -
     * telling them which role would open it is a small piece of the system's shape given away for
     * no benefit to anyone entitled to be there.
     *
     * <p>SRS section 5.3 has no message for a refused authorization. MSG43's reference-code text is
     * borrowed while the alignment item asking for a proper one is open, the same way
     * {@link #DATA_CONFLICT} borrows it, and the detail sentence carries the real meaning so that a
     * client which shows the detail is not made to lie. A correctly built client should not reach
     * this code at all: the screens a role cannot open are not offered to it, so a 403 here means
     * either a direct call to the API or a defect.
     */
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "MSG43");

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
