package com.cryptopilot.ai.calculator;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

/**
 * The server-side guardrail of UC-32 (TECHNICAL_DESIGN 7.14 step 5): every answer ends with the active disclaimer
 * (BR-51). The model is asked to write it; when it did not, or wrote it differently, it is appended. The comparison
 * ignores case, accents, punctuation and spacing, so "Thông tin chỉ mang tính tham khảo…" written by the model counts.
 *
 * <p>Rule: BR-51; UC-32.
 */
public final class AnswerGuard {

    private AnswerGuard() {}

    /** The answer, trimmed, with the disclaimer appended on its own paragraph when it is missing. */
    public static String withDisclaimer(String answer, String disclaimer) {
        Objects.requireNonNull(answer, "answer");
        String text = answer.strip();
        if (disclaimer == null || disclaimer.isBlank()) {
            return text;
        }
        if (fold(text).contains(fold(disclaimer))) {
            return text;
        }
        return text.isEmpty() ? disclaimer.strip() : text + "\n\n" + disclaimer.strip();
    }

    /** Lower case, no accents (đ as d), letters and digits only. */
    static String fold(String text) {
        String decomposed = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replace('đ', 'd');
        StringBuilder out = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (Character.isLetterOrDigit(c) && Character.getType(c) != Character.NON_SPACING_MARK) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
