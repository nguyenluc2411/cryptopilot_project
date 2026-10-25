package com.cryptopilot.academy.service.impl;

import com.cryptopilot.common.exception.FieldValidationException;
import java.util.Map;

/**
 * The search text of a {@code q} parameter of the Academy: stripped, {@code null} when blank, and 2 to 100 characters
 * otherwise (MSG15).
 *
 * <p>Rule: T-114.
 */
final class SearchText {

    private static final int MIN_LENGTH = 2;
    private static final int MAX_LENGTH = 100;

    private SearchText() {}

    static String of(String text) {
        String stripped = blankToNull(text);
        if (stripped != null && (stripped.length() < MIN_LENGTH || stripped.length() > MAX_LENGTH)) {
            throw new FieldValidationException(
                    "q must be " + MIN_LENGTH + " to " + MAX_LENGTH + " characters", Map.of("q", "MSG15"));
        }
        return stripped;
    }

    static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
