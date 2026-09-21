package com.cryptopilot.common.exception;

import org.springframework.http.ProblemDetail;

/**
 * Builds the one response body an API error has, so that the two places which produce one cannot
 * drift apart.
 *
 * <h2>Why a second producer exists at all</h2>
 *
 * <p>{@link GlobalExceptionHandler} answers everything that reaches the web boundary as an
 * exception. A request refused by the security filter chain never gets that far: it is refused
 * before the dispatcher runs, so no {@code @ExceptionHandler} sees it, and Spring Security writes
 * the status with an empty body unless something is given to it. That "something" lives in
 * {@code auth.config} because it is a Spring Security type, and it has to produce a body identical
 * to the advice's - same properties, same names, same spelling - or a client would need two parsers
 * for one API. This class is the shared piece that makes them identical by construction rather than
 * by two people remembering.
 *
 * <p>The property names are public for the same reason: a test asserts against the name a client
 * reads, and a literal in a test is a copy that can agree with a typo.
 *
 * <p>Rule: TECHNICAL_DESIGN section 5.1; SRS 4.2.4.
 *
 * <p>Reference: Nottingham, M., Wilde, E. &amp; Dalal, S. (2023). RFC 9457, <i>Problem Details for
 * HTTP APIs</i>, section 3.2 (extension members carry what the standard members cannot).
 */
public final class ProblemDetails {

    /** The stable identifier a client branches on. */
    public static final String CODE = "code";

    /** The SRS section 5.3 message whose text the client displays. */
    public static final String MESSAGE_CODE = "messageCode";

    /** The correlation id tying this response to the log lines of the same request. */
    public static final String TRACE_ID = "traceId";

    private ProblemDetails() {}

    /**
     * A problem detail for this error, with the status the error names and the three properties
     * every error response carries.
     *
     * @param detail the human-readable sentence; it names no table, no constraint and no stack frame
     * @param traceId the correlation id, or a reference code when the detail deliberately says
     *     nothing; {@code null} leaves the property out rather than writing an empty one
     */
    public static ProblemDetail of(ErrorCode errorCode, String detail, String traceId) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(errorCode.status(), detail);
        body.setTitle(errorCode.status().getReasonPhrase());
        body.setProperty(CODE, errorCode.code());
        body.setProperty(MESSAGE_CODE, errorCode.messageCode());
        if (traceId != null) {
            body.setProperty(TRACE_ID, traceId);
        }
        return body;
    }
}
