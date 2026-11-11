package com.cryptopilot.ai.dto.response;

import java.time.Instant;

/**
 * Today's AI questions of the caller (BR-50): what the chat panel shows beside the input.
 *
 * @param limit the plan's daily quota, {@code null} when unlimited; 0 on FREE
 * @param used the questions counted today (pending or completed)
 * @param remaining what is left, {@code null} when unlimited
 * @param resetsAt the next 00:00 UTC+7
 * @param canAsk whether a question can be asked now; false on FREE (MSG29, BR-52) or with the quota used up (MSG30)
 * @param performanceContext whether the plan adds the performance summary (PREMIUM)
 */
public record QuotaResponse(
        Integer limit, long used, Integer remaining, Instant resetsAt, boolean canAsk, boolean performanceContext) {}
