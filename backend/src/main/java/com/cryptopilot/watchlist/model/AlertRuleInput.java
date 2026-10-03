package com.cryptopilot.watchlist.model;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.watchlist.model.enums.AlertIndicator;
import com.cryptopilot.watchlist.model.enums.AlertType;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import com.cryptopilot.watchlist.model.enums.TriggerMode;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * An alert rule as the Trader entered it, before the combination of its fields is checked. A field that does not
 * apply to the chosen type is {@code null}; when it is not, the rule is refused rather than the value dropped.
 *
 * <p>Rule: SRS 3.4.2; BR-19, BR-20.
 *
 * @param market SPOT or FUTURES
 * @param type PRICE or INDICATOR
 * @param indicator the indicator of an INDICATOR alert
 * @param timeframe 15m, 1h, 4h or 1d for RSI_14, MACD_CROSS and EMA_CROSS
 * @param condition how the value is compared with the threshold
 * @param threshold the target price, the RSI level, the funding rate or the open interest change
 * @param triggerMode ONCE, ONCE_PER_BAR or EVERY_TIME
 * @param cooldownMinutes the least time between two firings, at least one minute
 * @param notifyEmail deliver by email as well as in the app
 * @param notifyPush deliver by push as well as in the app
 * @param expiresAt when the alert stops being evaluated, or {@code null} for never
 */
public record AlertRuleInput(
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
        Instant expiresAt) {}
