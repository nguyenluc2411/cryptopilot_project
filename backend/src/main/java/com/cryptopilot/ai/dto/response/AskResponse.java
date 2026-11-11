package com.cryptopilot.ai.dto.response;

/**
 * The stored question, PENDING, and the quota after it (SRS 3.9.1). The answer follows on {@code /user/queue/ai} as an
 * {@link AiAnswerPush}.
 *
 * @param question the question
 * @param quota today's quota, this question counted
 */
public record AskResponse(MessageResponse question, QuotaResponse quota) {}
