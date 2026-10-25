package com.cryptopilot.news.calculator;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What an AI summary must be before it is stored (NSF-11): not empty, at most the configured number of words, free of
 * any instruction to buy or sell, and stating no figure the article does not contain ({@link NumericGuard}). The
 * reason a summary fails is written for the model, which is asked once more with it.
 *
 * <p>Words are counted as runs of characters between spaces, which counts a Vietnamese syllable as a word: the limit
 * is never more lenient than it reads.
 *
 * <p>Rule: NSF-11 (a summary of at most 60 words); the hard scope boundary (no investment advice); BR-51.
 */
public final class SummaryCheck {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final String ELLIPSIS = "…";

    /** Phrases that tell the reader to trade, in the two languages of the product. */
    private static final Pattern ADVICE = Pattern.compile(
            "\\b(?:nên|hãy|khuyến nghị|khuyên)\\s+(?:mua|bán|nắm giữ|giữ|chốt lời|cắt lỗ)\\b"
                    + "|\\b(?:mua|bán)\\s+ngay\\b"
                    + "|\\b(?:you\\s+)?should\\s+(?:buy|sell|hold|short)\\b"
                    + "|\\b(?:buy|sell)\\s+now\\b"
                    + "|\\btime\\s+to\\s+(?:buy|sell)\\b"
                    + "|\\bwe\\s+recommend\\s+(?:buying|selling)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    private SummaryCheck() {}

    /** Why {@code summary} cannot be stored as the summary of {@code article}, or empty when it can. */
    public static Optional<String> problem(String summary, String article, int maxWords) {
        if (summary == null || summary.isBlank()) {
            return Optional.of("the summary is empty");
        }
        int words = words(summary);
        if (words > maxWords) {
            return Optional.of("the summary has " + words + " words, more than " + maxWords);
        }
        Matcher advice = ADVICE.matcher(summary);
        if (advice.find()) {
            return Optional.of(
                    "the summary tells the reader to trade (\"" + advice.group() + "\"); report, do not advise");
        }
        List<String> invented = NumericGuard.unsupported(summary, article);
        if (!invented.isEmpty()) {
            return Optional.of("these figures are not in the article: " + String.join(", ", invented));
        }
        return Optional.empty();
    }

    /** Whether the only problem of a summary is its length, which cutting it fixes. */
    public static boolean onlyTooLong(String summary, String article, int maxWords) {
        return summary != null
                && words(summary) > maxWords
                && problem(firstWords(summary, maxWords), article, maxWords).isEmpty();
    }

    /** The first {@code maxWords} words, marked as cut, so a summary that is otherwise sound is not thrown away. */
    public static String cut(String summary, int maxWords) {
        String first = firstWords(summary, maxWords);
        return first.endsWith(".") ? first : first + ELLIPSIS;
    }

    static int words(String text) {
        String trimmed = text.strip();
        return trimmed.isEmpty() ? 0 : WHITESPACE.split(trimmed).length;
    }

    private static String firstWords(String text, int count) {
        String[] words = WHITESPACE.split(text.strip());
        if (words.length <= count) {
            return text.strip();
        }
        return String.join(" ", Arrays.copyOf(words, count)).replaceAll("[,;:\\-–—]+$", "");
    }
}
