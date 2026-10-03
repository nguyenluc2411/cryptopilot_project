package com.cryptopilot.watchlist.model;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.watchlist.model.enums.AlertIndicator;
import com.cryptopilot.watchlist.model.enums.AlertType;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import com.cryptopilot.watchlist.model.enums.TriggerMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * An alert rule whose fields have been checked together, with the values the server fixes filled in: the 1h
 * timeframe of FUNDING_RATE and OPEN_INTEREST_CHANGE (A-40); a line cross has no threshold (D-76). What an
 * {@link com.cryptopilot.watchlist.entity.Alert} stores.
 *
 * <p>Rule: SRS 3.4.2; BR-19, BR-20; A-40.
 *
 * @param market SPOT or FUTURES
 * @param type PRICE or INDICATOR
 * @param indicator the indicator, {@code null} for PRICE
 * @param timeframe the candle timeframe the indicator is read on, {@code null} for PRICE
 * @param condition how the value is compared with the threshold
 * @param threshold the value compared with; a PRICE target is on the tick; {@code null} for MACD_CROSS and EMA_CROSS
 * @param triggerMode ONCE, ONCE_PER_BAR or EVERY_TIME
 * @param cooldownMinutes the least time between two firings, or {@code null}; set for EVERY_TIME
 * @param notifyEmail deliver by email as well
 * @param notifyPush deliver by push as well
 * @param expiresAt when the alert stops being evaluated, or {@code null}
 */
public record AlertDefinition(
        MarketType market,
        AlertType type,
        AlertIndicator indicator,
        String timeframe,
        ConditionOperator condition,
        BigDecimal threshold,
        TriggerMode triggerMode,
        Integer cooldownMinutes,
        boolean notifyEmail,
        boolean notifyPush,
        Instant expiresAt) {

    public AlertDefinition {
        Objects.requireNonNull(market, "market must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(condition, "condition must not be null");
        Objects.requireNonNull(triggerMode, "triggerMode must not be null");
        if ((type == AlertType.PRICE) != (indicator == null) || (indicator == null) != (timeframe == null)) {
            throw new IllegalArgumentException(
                    "a PRICE alert names no indicator or timeframe, an INDICATOR alert both");
        }
        if ((threshold == null) != (indicator != null && indicator.signCross())) {
            throw new IllegalArgumentException("every alert but a line cross has a threshold");
        }
    }

    /** The same rule with another threshold: the target price once it is on the tick. */
    public AlertDefinition withThreshold(BigDecimal value) {
        return new AlertDefinition(
                market,
                type,
                indicator,
                timeframe,
                condition,
                value,
                triggerMode,
                cooldownMinutes,
                notifyEmail,
                notifyPush,
                expiresAt);
    }
}
