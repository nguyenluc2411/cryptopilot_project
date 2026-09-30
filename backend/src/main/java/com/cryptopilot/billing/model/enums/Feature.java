package com.cryptopilot.billing.model.enums;

import com.cryptopilot.billing.PlanEntitlements;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The twelve entitlements of a plan tier (Table 3.1), each either a switch the plan turns on or off, or a limit the
 * plan sets — a maximum of items, or {@link #AI_CHAT_DAILY}, the daily question quota.
 *
 * <p>Rule: BR-62, BR-50; SRS v1.1 Table 3.1, UC-53; D-58.
 */
public enum Feature {

    /** Futures analysis, Futures plans and Futures alerts. */
    FUTURES_ANALYSIS(PlanEntitlements::futuresAnalysis),

    /** The component scores of the setup score. */
    SCORE_COMPONENTS(PlanEntitlements::scoreComponents),

    /** ACTIVE plans and open simulated positions. */
    ACTIVE_PLAN_MAX(PlanEntitlements::activePlanMax, "active plans and open positions"),

    /** Watchlist items (BR-15). */
    WATCHLIST_MAX(PlanEntitlements::watchlistMax, "watchlist items"),

    /** ACTIVE alerts (BR-17). */
    ACTIVE_ALERT_MAX(PlanEntitlements::activeAlertMax, "active alerts"),

    /** INDICATOR alerts. */
    INDICATOR_ALERT(PlanEntitlements::indicatorAlert),

    /** Email and push delivery of alerts. */
    EXTERNAL_ALERT_CHANNELS(PlanEntitlements::externalAlertChannels),

    /** The full performance dashboard. */
    ADVANCED_PERFORMANCE(PlanEntitlements::advancedPerformance),

    /** AI summary and sentiment of news. */
    NEWS_AI_INSIGHT(PlanEntitlements::newsAiInsight),

    /** AI Assistant questions per day (BR-50); a quota of 0 means the plan has no AI Assistant. */
    AI_CHAT_DAILY(e -> e.aiChatDaily(), "AI questions today"),

    /** The own performance summary in the AI context. */
    AI_PERFORMANCE_CONTEXT(PlanEntitlements::aiPerformanceContext),

    /** Video posts. */
    VIDEO_POST(PlanEntitlements::videoPost);

    /** Whether a feature is turned on or off, or limited by a number. */
    public enum Kind {
        SWITCH,
        LIMIT
    }

    private final Kind kind;
    private final Predicate<PlanEntitlements> switchOf;
    private final Function<PlanEntitlements, Integer> limitOf;
    private final String items;

    Feature(Predicate<PlanEntitlements> switchOf) {
        this.kind = Kind.SWITCH;
        this.switchOf = switchOf;
        this.limitOf = null;
        this.items = null;
    }

    Feature(Function<PlanEntitlements, Integer> limitOf, String items) {
        this.kind = Kind.LIMIT;
        this.switchOf = null;
        this.limitOf = limitOf;
        this.items = items;
    }

    public Kind kind() {
        return kind;
    }

    /** What a limit counts, as MSG27 names it, e.g. {@code watchlist items}; {@code null} for a switch. */
    public String items() {
        return items;
    }

    /**
     * Whether {@code entitlements} include the feature: a switch that is on, or a limit that is unlimited or above 0.
     */
    public boolean includedIn(PlanEntitlements entitlements) {
        if (kind == Kind.SWITCH) {
            return switchOf.test(entitlements);
        }
        Integer limit = limitOf.apply(entitlements);
        return limit == null || limit > 0;
    }

    /**
     * The limit {@code entitlements} set, {@code null} meaning unlimited.
     *
     * @throws IllegalArgumentException for a switch, which has no limit
     */
    public Integer limitIn(PlanEntitlements entitlements) {
        if (kind != Kind.LIMIT) {
            throw new IllegalArgumentException(this + " is a switch, not a limit");
        }
        return limitOf.apply(entitlements);
    }
}
