package com.cryptopilot.watchlist.model;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import com.cryptopilot.watchlist.model.enums.TriggerMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An ACTIVE PRICE alert as the repository reads it, with the pair of its watchlist row; the symbol, which the
 * {@code market} module owns, is added to make a {@link PriceAlert}.
 *
 * <p>Rule: NSF-06; TECHNICAL_DESIGN 7.9.
 */
public record PriceAlertRow(
        UUID alertId,
        UUID userId,
        UUID watchlistId,
        UUID pairId,
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

    /** The alert with its pair's symbol. */
    public PriceAlert withSymbol(String symbol) {
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
                triggerCount,
                lastTriggeredAt,
                lastBarOpenTime,
                version);
    }
}
