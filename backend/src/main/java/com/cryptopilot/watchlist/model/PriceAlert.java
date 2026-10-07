package com.cryptopilot.watchlist.model;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import com.cryptopilot.watchlist.model.enums.TriggerMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An ACTIVE PRICE alert as the alert engine holds it in memory: the rule, the pair it watches and the trigger
 * bookkeeping last read from or written to the database. Immutable; a trigger gives a new value.
 *
 * <p>Rule: NSF-06, BR-18, BR-19, BR-20; TECHNICAL_DESIGN 7.9.
 *
 * @param alertId the alert
 * @param userId its owner
 * @param watchlistId the watchlist row it hangs on
 * @param pairId the watched pair
 * @param symbol the pair's symbol
 * @param market the market whose last price it watches
 * @param condition the comparison
 * @param threshold the target price
 * @param triggerMode ONCE, ONCE_PER_BAR or EVERY_TIME
 * @param cooldownMinutes the cooldown of EVERY_TIME; may be {@code null} for the other modes
 * @param expiresAt when it stops being evaluated, or {@code null}
 * @param notifyInApp always true
 * @param notifyEmail whether an email is wanted
 * @param notifyPush whether a push notification is wanted
 * @param triggerCount how many times it has fired
 * @param lastTriggeredAt when it last fired, or {@code null}
 * @param lastBarOpenTime the open time of the 1h candle it last fired on, or {@code null}
 * @param version the row version the bookkeeping was read at; a trigger is recorded only on this version
 */
public record PriceAlert(
        UUID alertId,
        UUID userId,
        UUID watchlistId,
        UUID pairId,
        String symbol,
        MarketType market,
        ConditionOperator condition,
        BigDecimal threshold,
        TriggerMode triggerMode,
        Integer cooldownMinutes,
        Instant expiresAt,
        boolean notifyInApp,
        boolean notifyEmail,
        boolean notifyPush,
        int triggerCount,
        Instant lastTriggeredAt,
        Instant lastBarOpenTime,
        long version) {

    public PriceAlert {
        Objects.requireNonNull(alertId, "alertId");
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(threshold, "threshold");
        Objects.requireNonNull(triggerMode, "triggerMode");
    }

    /** The alert after a trigger this engine recorded, with the row version the update produced. */
    public PriceAlert triggeredAt(Instant at, Instant barOpenTime) {
        return new PriceAlert(
                alertId,
                userId,
                watchlistId,
                pairId,
                symbol,
                market,
                condition,
                threshold,
                triggerMode,
                cooldownMinutes,
                expiresAt,
                notifyInApp,
                notifyEmail,
                notifyPush,
                triggerCount + 1,
                at,
                barOpenTime,
                version + 1);
    }
}
