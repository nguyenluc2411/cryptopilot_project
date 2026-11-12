package com.cryptopilot.ai.event;

import java.util.Objects;
import java.util.UUID;

/**
 * A Trader's question was stored PENDING and waits for its answer (NSF-16, TECHNICAL_DESIGN 7.14 step 2). Published
 * in the transaction that stores it, so the registry delivers it only after the question is committed.
 *
 * @param questionId the USER message
 * @param conversationId its conversation
 * @param userId the Trader, to push the outcome to
 */
public record AiQuestionAsked(UUID questionId, UUID conversationId, UUID userId) {

    public AiQuestionAsked {
        Objects.requireNonNull(questionId, "questionId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(userId, "userId");
    }
}
