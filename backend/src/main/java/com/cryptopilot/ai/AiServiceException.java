package com.cryptopilot.ai;

import com.cryptopilot.common.exception.ExternalAnswer;
import java.util.Objects;

/**
 * The AI Service could not answer. {@code code} is the AI Service's own (for example {@code LLM_RATE_LIMITED}), or
 * {@code NOT_CONFIGURED} and {@code UNREACHABLE} from this side; the message never carries a secret.
 */
public class AiServiceException extends RuntimeException implements ExternalAnswer {

    private static final long serialVersionUID = 1L;

    /** The AI Service address or shared secret is not set, so nothing was sent. */
    public static final String NOT_CONFIGURED = "NOT_CONFIGURED";

    /** The AI Service did not answer: down, unreachable, or too slow. */
    public static final String UNREACHABLE = "UNREACHABLE";

    /** The provider limits the rate; more requests now would be refused too. */
    public static final String RATE_LIMITED = "LLM_RATE_LIMITED";

    /** The answer was not what the contract says, so the service did not really answer. */
    public static final String BAD_ANSWER = "BAD_ANSWER";

    private final String code;
    private final boolean retryable;
    /** The HTTP status the AI Service answered with, or 0 when it did not answer. */
    private final int status;

    public AiServiceException(String code, boolean retryable, String message) {
        this(code, retryable, message, null);
    }

    public AiServiceException(String code, boolean retryable, String message, Throwable cause) {
        this(code, retryable, message, cause, 0);
    }

    public AiServiceException(String code, boolean retryable, String message, Throwable cause, int status) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.retryable = retryable;
        this.status = status;
    }

    /** The HTTP status the AI Service answered with, or 0 when it did not answer. */
    public int status() {
        return status;
    }

    /**
     * A non-retryable 4xx other than 401/403 (a wrong secret breaks every call). Reference: RFC 9110 §15.5.2, §15.5.4.
     */
    @Override
    public boolean serviceAnswered() {
        return status >= 400
                && status < 500
                && status != 401
                && status != 403
                && !retryable
                && !BAD_ANSWER.equals(code);
    }

    public String code() {
        return code;
    }

    /** Whether the same request may succeed later, so the caller keeps the work for its next run. */
    public boolean retryable() {
        return retryable;
    }

    /** Whether more requests now are pointless: the service is missing, down, or the provider rate limits. */
    public boolean stopsTheRun() {
        return code.equals(NOT_CONFIGURED) || code.equals(UNREACHABLE) || code.equals(RATE_LIMITED);
    }
}
