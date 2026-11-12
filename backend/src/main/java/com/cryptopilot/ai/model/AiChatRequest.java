package com.cryptopilot.ai.model;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * One call of {@code POST /v1/chat} (T-072): the active configuration, the history oldest first, the question and its
 * context.
 *
 * @param context the stored snapshot, or {@code null} for a general question
 * @param timeout how long the call may take; past it the question fails (SRS 3.9.1: 60 seconds)
 */
public record AiChatRequest(
        String model,
        String systemPrompt,
        BigDecimal temperature,
        int maxOutputTokens,
        List<ChatTurn> history,
        String question,
        JsonNode context,
        Duration timeout) {

    public AiChatRequest {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(temperature, "temperature");
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(timeout, "timeout");
        history = history == null ? List.of() : List.copyOf(history);
    }
}
