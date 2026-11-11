package com.cryptopilot.ai.dto.request;

import com.cryptopilot.market.model.enums.MarketType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * One question with the context selector of SCR-29 (SRS 3.9.1). Every context field is optional: the pair defaults to
 * the conversation's, the market and timeframe to the plan's when a plan is chosen, else Spot and 1h.
 *
 * @param question 1–1,000 characters
 * @param pairId the pair, or {@code null} for the conversation's
 * @param market SPOT or FUTURES, or {@code null}
 * @param timeframe {@code 15m}, {@code 1h}, {@code 4h} or {@code 1d}, or {@code null}
 * @param planId one of the caller's plans, or {@code null}
 * @param includePerformance true to add the performance summary (PREMIUM); default false
 */
public record AskQuestionRequest(
        @NotBlank(message = "MSG01") @Size(max = 1000, message = "MSG01")
        String question,

        UUID pairId,
        MarketType market,
        @Pattern(regexp = "15m|1h|4h|1d", message = "MSG01") String timeframe,
        UUID planId,
        Boolean includePerformance) {}
