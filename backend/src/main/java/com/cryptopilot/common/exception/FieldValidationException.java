package com.cryptopilot.common.exception;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A business rule that rejects named fields of a request, reported the way a request body that failed Bean
 * Validation is: {@link ErrorCode#VALIDATION_FAILED} (MSG01, 400), or a 400 code of its own, with one entry per field
 * under {@code errors}, the value being the SRS message code shown under that field (MSG15, MSG16 …). It is for rules
 * that only a domain calculation can check, such as the price order of a plan recalculated at activation.
 *
 * <p>Rule: TECHNICAL_DESIGN section 5.1; SRS section 5.3 (MSG01, MSG15, MSG16).
 */
public class FieldValidationException extends BusinessException {

    private static final long serialVersionUID = 1L;

    /** A {@code LinkedHashMap} rather than a {@code Map}: the exception is serializable, and the order is the rules'. */
    private final LinkedHashMap<String, String> errors;

    /**
     * @param detail a sentence for developers and logs
     * @param errors field → SRS message code, at least one entry
     */
    public FieldValidationException(String detail, Map<String, String> errors) {
        this(ErrorCode.VALIDATION_FAILED, detail, errors);
    }

    /**
     * A field rule with its own 400 code, for a client that branches on it rather than on the field's message.
     *
     * @param errorCode a 400 code
     * @param detail a sentence for developers and logs
     * @param errors field → SRS message code, at least one entry
     */
    public FieldValidationException(ErrorCode errorCode, String detail, Map<String, String> errors) {
        super(errorCode, detail);
        if (errorCode.status().value() != 400) {
            throw new IllegalArgumentException("a field validation answers 400, not " + errorCode.status());
        }
        if (errors.isEmpty()) {
            throw new IllegalArgumentException("a field validation names at least one field");
        }
        this.errors = new LinkedHashMap<>(errors);
    }

    /** Field → SRS message code, in the order the rules reported them. A copy. */
    public Map<String, String> errors() {
        return new LinkedHashMap<>(errors);
    }
}
