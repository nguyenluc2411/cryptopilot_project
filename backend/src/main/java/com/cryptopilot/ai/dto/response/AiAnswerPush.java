package com.cryptopilot.ai.dto.response;

import com.cryptopilot.ai.model.enums.MessageStatus;
import java.util.UUID;

/**
 * The outcome of a question, pushed to the Trader's {@code /user/queue/ai} (NSF-16): COMPLETED with the answer, or
 * FAILED (MSG31) without one. The chat panel replaces the typing indicator with it.
 *
 * @param conversationId the conversation
 * @param questionId the question
 * @param status COMPLETED or FAILED
 * @param answer the answer, or {@code null} when it failed
 */
public record AiAnswerPush(UUID conversationId, UUID questionId, MessageStatus status, MessageResponse answer) {}
