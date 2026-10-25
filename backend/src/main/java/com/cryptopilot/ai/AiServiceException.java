package com.cryptopilot.ai;

import java.util.Objects;

/**
 * The AI Service could not answer. {@code code} is the AI Service's own (for example {@code LLM_RATE_LIMITED}), or
 * {@code NOT_CONFIGURED} and {@code UNREACHABLE} from this side; the message never carries a secret.
 */
public class AiServiceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The AI Service address or shared secret is not set, so nothing was sent. */
    public static final String NOT_CONFIGURED = "NOT_CONFIGURED";

    /** The AI Service did not answer: down, unreachable, or too slow. */
    public static final String UNREACHABLE = "UNREACHABLE";

    /** The provider limits the rate; more requests now would be refused too. */
    public static final String RATE_LIMITED = "LLM_RATE_LIMITED";

    private final String code;
    private final boolean retryable;

    public AiServiceException(String code, boolean retryable, String message) {
        this(code, retryable, message, null);
    }

    public AiServiceException(String code, boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.retryable = retryable;
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
