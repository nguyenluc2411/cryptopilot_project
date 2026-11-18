package com.cryptopilot.ai.dto.response;

import com.cryptopilot.ai.model.enums.MessageStatus;
import com.cryptopilot.ai.model.enums.SenderRole;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A question or an answer of a conversation (SRS 3.9.1). A question is PENDING while the typing indicator shows,
 * then COMPLETED, or FAILED with MSG31; an answer always ends with the disclaimer (BR-51).
 *
 * @param id the message
 * @param conversationId its conversation
 * @param role USER or ASSISTANT
 * @param content the question or the answer
 * @param status PENDING, COMPLETED or FAILED
 * @param createdAt when it was written
 * @param sources the articles an answer cites as {@code [n]}, in citation order; empty on a question and on an answer
 *     citing none (T-074)
 */
public record MessageResponse(
        UUID id,
        UUID conversationId,
        SenderRole role,
        String content,
        MessageStatus status,
        Instant createdAt,
        List<MessageSourceResponse> sources) {}
