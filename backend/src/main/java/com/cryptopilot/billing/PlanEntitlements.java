package com.cryptopilot.billing;

/**
 * What one plan tier allows: seven switches, three maxima and the daily AI quota. A {@code null} maximum is
 * unlimited; a maximum or quota below zero is refused.
 *
 * <p>Rule: BR-62; SRS v1.1 Table 3.1, entity 34; D-58.
 *
 * @param futuresAnalysis {@code FUTURES_ANALYSIS}: Futures analysis, plans and alerts
 * @param scoreComponents {@code SCORE_COMPONENTS}: the component scores of the setup score
 * @param activePlanMax {@code ACTIVE_PLAN_MAX}: ACTIVE plans and open simulated positions, or {@code null}
 * @param watchlistMax {@code WATCHLIST_MAX}: watchlist items, or {@code null}
 * @param activeAlertMax {@code ACTIVE_ALERT_MAX}: ACTIVE alerts, or {@code null}
 * @param indicatorAlert {@code INDICATOR_ALERT}: indicator alerts
 * @param externalAlertChannels {@code EXTERNAL_ALERT_CHANNELS}: email and push delivery of alerts
 * @param advancedPerformance {@code ADVANCED_PERFORMANCE}: the full performance dashboard
 * @param newsAiInsight {@code NEWS_AI_INSIGHT}: AI summary and sentiment of news
 * @param aiChatDaily {@code AI_CHAT_DAILY}: AI Assistant questions per day
 * @param aiPerformanceContext {@code AI_PERFORMANCE_CONTEXT}: the own performance summary in the AI context
 * @param videoPost {@code VIDEO_POST}: video posts
 */
public record PlanEntitlements(
        boolean futuresAnalysis,
        boolean scoreComponents,
        Integer activePlanMax,
        Integer watchlistMax,
        Integer activeAlertMax,
        boolean indicatorAlert,
        boolean externalAlertChannels,
        boolean advancedPerformance,
        boolean newsAiInsight,
        int aiChatDaily,
        boolean aiPerformanceContext,
        boolean videoPost) {

    public PlanEntitlements {
        requireNotNegative(activePlanMax, "activePlanMax");
        requireNotNegative(watchlistMax, "watchlistMax");
        requireNotNegative(activeAlertMax, "activeAlertMax");
        requireNotNegative(aiChatDaily, "aiChatDaily");
    }

    private static void requireNotNegative(Integer value, String name) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(name + " must not be negative, was " + value);
        }
    }
}
