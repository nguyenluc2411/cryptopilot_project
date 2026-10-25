package com.cryptopilot.ai;

import java.util.Objects;

/**
 * One article to summarize, with the model and the instructions to use (NSF-11).
 *
 * @param model the provider's model identifier, from the caller's configuration
 * @param systemPrompt the instructions, from the caller's configuration
 * @param temperature the sampling temperature, 0 to 2; a provider setting, never money
 * @param maxOutputTokens the longest answer, in tokens
 * @param title the headline
 * @param source the outlet's name, or {@code null}
 * @param text the material to summarize
 * @param feedback why the caller refused the previous answer for this article, or {@code null}
 */
public record NewsSummaryRequest(
        String model,
        String systemPrompt,
        double temperature,
        int maxOutputTokens,
        String title,
        String source,
        String text,
        String feedback) {

    public NewsSummaryRequest {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(text, "text must not be null");
    }

    /** The same request carrying the reason the previous answer was refused. */
    public NewsSummaryRequest withFeedback(String reason) {
        return new NewsSummaryRequest(model, systemPrompt, temperature, maxOutputTokens, title, source, text, reason);
    }
}
