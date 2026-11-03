package com.cryptopilot.common.exception;

/**
 * A failure of a call to an external service that may still be the service answering: a 4xx refusal means the service
 * is up and refused this request, so the health panel counts it as an answer, not an outage. Lives in the shared kernel
 * so the system log reads it without depending on the modules that own the clients.
 *
 * <p>Rule: UC-46. Reference: Fielding, R. &amp; Reschke, J., RFC 9110, §15.5 (4xx: client error, the server answered).
 */
public interface ExternalAnswer {

    /** Whether the service answered and refused this request, rather than failing to answer. */
    boolean serviceAnswered();
}
