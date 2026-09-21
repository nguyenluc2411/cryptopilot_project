package com.cryptopilot.common.exception;

import java.util.List;
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
 * <h2>Message arguments</h2>
 *
 * <p>Some messages of SRS section 5.3 are sentences with a hole in them: MSG09 is "Please try again
 * after {minutes} minutes" and MSG10 is "Your account has been {status}". The client owns the
 * wording, but only the server knows the value, so the value travels with the rejection and the
 * client substitutes it. Without this, those two messages could not be displayed at all without the
 * backend either sending the finished sentence - which would put a copy of SRS 5.3 in two
 * repositories - or the client guessing.
 *
 * <p>The arguments are positional, in the order the message's placeholders appear, and they carry no
 * names because a message code plus a position is already a contract between the two sides. They are
 * values, never secrets: an argument ends up in a response body, so a token, a hash or an address
 * belonging to somebody else has no business being one.
 *
 * <p>Rule: TECHNICAL_DESIGN section 5.1; SRS section 5.3 (MSG09, MSG10).
 *
 * <p>Reference: Bloch, J. (2018). <i>Effective Java</i> (3rd ed.). Addison-Wesley, Item 70
 * (unchecked exceptions for conditions the caller cannot usefully recover from) and Item 75 (a
 * detail message states the values that failed the check).
 */
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private static final String[] NO_ARGS = {};

    private final ErrorCode errorCode;

    /**
     * Text, and an array rather than a list, for one reason each. Text because an argument is a piece
     * of a sentence the client is about to assemble, so rendering it once here beats leaving every
     * client to decide how a number or an enumeration constant looks. An array because an exception is
     * serializable and {@code List<Object>} is not, which the compiler says out loud and this project
     * treats as an error.
     */
    private final String[] messageArgs;

    /**
     * @param errorCode the catalogue entry describing the rejection, never {@code null}
     * @param detail a non-blank sentence naming what was refused and why
     */
    public BusinessException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, (Throwable) null);
    }

    /**
     * @param errorCode the catalogue entry describing the rejection, never {@code null}
     * @param detail a non-blank sentence naming what was refused and why
     * @param cause the technical failure behind the rejection, or {@code null}
     */
    public BusinessException(ErrorCode errorCode, String detail, Throwable cause) {
        super(requireText(detail), cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
        this.messageArgs = NO_ARGS;
    }

    /**
     * A rejection whose SRS message is a sentence with a hole in it.
     *
     * <p>A single {@link Throwable} argument selects the constructor above instead of this one, which
     * is what Java's overload resolution does with a varargs method and is also what a caller passing
     * a cause means. A cause and message arguments together have no caller yet and no shape to guess
     * at, so there is deliberately no constructor for both.
     *
     * @param errorCode the catalogue entry describing the rejection, never {@code null}
     * @param detail a non-blank sentence naming what was refused and why
     * @param messageArgs the values the message's placeholders take, in the order they appear, each
     *     rendered with {@code toString}; never {@code null}, and never a secret, because each one is
     *     returned in the response body
     */
    public BusinessException(ErrorCode errorCode, String detail, Object... messageArgs) {
        super(requireText(detail));
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
        this.messageArgs = new String[messageArgs.length];
        for (int i = 0; i < messageArgs.length; i++) {
            this.messageArgs[i] = Objects.requireNonNull(messageArgs[i], "a message argument must not be null")
                    .toString();
        }
    }

    /** The catalogue entry that decides the status and the SRS message code of the response. */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /**
     * The values the SRS message's placeholders take, in order, or an empty list for a message that
     * has none. A copy, so a handler cannot change what a rule decided.
     */
    public List<String> messageArgs() {
        return List.of(messageArgs);
    }

    private static String requireText(String detail) {
        if (detail == null || detail.isBlank()) {
            throw new IllegalArgumentException("detail must not be blank");
        }
        return detail;
    }
}
