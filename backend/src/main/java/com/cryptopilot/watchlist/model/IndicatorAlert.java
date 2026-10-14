package com.cryptopilot.watchlist.model;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.watchlist.model.enums.AlertIndicator;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import com.cryptopilot.watchlist.model.enums.TriggerMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An ACTIVE INDICATOR alert as the repository reads it for one closed candle, with the pair of its watchlist row.
 *
 * <p>Rule: NSF-06, BR-18, BR-19, BR-20; TECHNICAL_DESIGN 7.9.
 *
 * @param alertId the alert
 * @param userId its owner
 * @param watchlistId the watchlist row it hangs on
 * @param pairId the watched pair
 * @param market the market of the candle series
 * @param indicator the indicator
 * @param timeframe the timeframe of the candle series
 * @param condition the comparison
 * @param threshold the level compared with, or {@code null} for a line cross (D-76)
 * @param triggerMode ONCE, ONCE_PER_BAR or EVERY_TIME
 * @param cooldownMinutes the cooldown of EVERY_TIME, or {@code null}
 * @param expiresAt when it stops being evaluated, or {@code null}
 * @param notifyInApp always true
 * @param notifyEmail whether an email is wanted
 * @param notifyPush whether a push notification is wanted
 * @param triggerCount how many times it has fired
 * @param version the row version read; a trigger is recorded only on this version
 */
public record IndicatorAlert(
        UUID alertId,
        UUID userId,
        UUID watchlistId,
        UUID pairId,
        MarketType market,
        AlertIndicator indicator,
        String timeframe,
        ConditionOperator condition,
        BigDecimal threshold,
        TriggerMode triggerMode,
        Integer cooldownMinutes,
        Instant expiresAt,
        boolean notifyInApp,
        boolean notifyEmail,
        boolean notifyPush,
        int triggerCount,
        long version) {}
