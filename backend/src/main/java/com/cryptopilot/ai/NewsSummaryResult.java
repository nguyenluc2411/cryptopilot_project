package com.cryptopilot.ai;

import java.util.Objects;

/**
 * The AI Service's answer for one article (NSF-11).
 *
 * @param title the article's headline in the summary's language, as the model wrote it
 * @param summary the summary, as the model wrote it
 * @param sentiment {@code BULLISH}, {@code NEUTRAL} or {@code BEARISH}
 * @param model the model that wrote it
 * @param inputTokens the tokens read
 * @param outputTokens the tokens written, reasoning included
 * @param body the article written again in the summary's language (D-129), or {@code null} when the answer has none
 */
public record NewsSummaryResult(
        String title, String summary, String sentiment, String model, int inputTokens, int outputTokens, String body) {

    public NewsSummaryResult {
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(summary, "summary must not be null");
        Objects.requireNonNull(sentiment, "sentiment must not be null");
    }

    /** An answer without the article written again. */
    public NewsSummaryResult(
            String title, String summary, String sentiment, String model, int inputTokens, int outputTokens) {
        this(title, summary, sentiment, model, inputTokens, outputTokens, null);
    }
}
