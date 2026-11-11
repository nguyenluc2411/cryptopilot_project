package com.cryptopilot.ai.model;

import java.util.Objects;
import java.util.UUID;

/**
 * A pending question read for its call to the AI Service (T-073): who to push the outcome to, and the call itself.
 *
 * @param questionId the question
 * @param conversationId its conversation
 * @param userId the Trader
 * @param request the call, or {@code null} when no configuration is active and the question can only fail
 * @param disclaimer the active disclaimer the answer must end with (BR-51), or {@code null} with no configuration
 */
public record PendingQuestion(
        UUID questionId, UUID conversationId, UUID userId, AiChatRequest request, String disclaimer) {

    public PendingQuestion {
        Objects.requireNonNull(questionId, "questionId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(userId, "userId");
    }
}
