package com.cryptopilot.billing.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * The limits and features of a plan tier, saved to every package of that tier (SCR-39, BR-62). A {@code null} count
 * means no limit.
 *
 * <p>Rule: SRS 3.11.5; BR-62 (Table 3.1); UC-42.
 *
 * @param futuresAnalysis Futures analysis on the analysis screens
 * @param scoreComponents the components of the setup score
 * @param activePlanMax the active trading plans, or {@code null} for no limit
 * @param watchlistMax the watched pairs, or {@code null} for no limit
 * @param activeAlertMax the active alerts, or {@code null} for no limit
 * @param indicatorAlert indicator alerts
 * @param externalAlertChannels alerts by email and push
 * @param advancedPerformance the advanced performance statistics
 * @param newsAiInsight the AI summary and sentiment of news
 * @param aiDailyQuota AI Assistant questions a day, 0 for none
 * @param aiPerformanceContext the Trader's performance in the AI Assistant's context
 * @param videoPost video in forum posts
 */
public record TierLimitsRequest(
        @NotNull(message = "MSG01") Boolean futuresAnalysis,
        @NotNull(message = "MSG01") Boolean scoreComponents,

        @Min(value = 0, message = "MSG15") @Max(value = 100_000, message = "MSG15")
        Integer activePlanMax,

        @Min(value = 0, message = "MSG15") @Max(value = 100_000, message = "MSG15")
        Integer watchlistMax,

        @Min(value = 0, message = "MSG15") @Max(value = 100_000, message = "MSG15")
        Integer activeAlertMax,

        @NotNull(message = "MSG01") Boolean indicatorAlert,
        @NotNull(message = "MSG01") Boolean externalAlertChannels,
        @NotNull(message = "MSG01") Boolean advancedPerformance,
        @NotNull(message = "MSG01") Boolean newsAiInsight,

        @NotNull(message = "MSG01") @Min(value = 0, message = "MSG15") @Max(value = 10_000, message = "MSG15")
        Integer aiDailyQuota,

        @NotNull(message = "MSG01") Boolean aiPerformanceContext,
        @NotNull(message = "MSG01") Boolean videoPost) {}
