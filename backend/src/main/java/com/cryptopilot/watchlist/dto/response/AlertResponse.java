package com.cryptopilot.watchlist.dto.response;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.watchlist.model.enums.AlertIndicator;
import com.cryptopilot.watchlist.model.enums.AlertStatus;
import com.cryptopilot.watchlist.model.enums.AlertType;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import com.cryptopilot.watchlist.model.enums.TriggerMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One alert as SCR-13 lists it and SCR-14 edits it.
 *
 * <p>Rule: UC-13, UC-14; SRS 3.4.2, 3.4.3.
 *
 * @param id the alert
 * @param watchlistId the watchlist row it belongs to (BR-16)
 * @param pairId the pair
 * @param symbol the pair's symbol
 * @param market SPOT or FUTURES
 * @param type PRICE or INDICATOR
 * @param indicator the indicator, {@code null} for PRICE
 * @param timeframe the timeframe, {@code null} for PRICE
 * @param condition the comparison
 * @param threshold the value compared with
 * @param triggerMode ONCE, ONCE_PER_BAR or EVERY_TIME
 * @param cooldownMinutes the cooldown, or {@code null}
 * @param notifyInApp always true
 * @param notifyEmail email delivery
 * @param notifyPush push delivery
 * @param status ACTIVE, PAUSED, TRIGGERED or EXPIRED
 * @param triggerCount how often it fired
 * @param lastTriggeredAt when it last fired, or {@code null}
 * @param expiresAt its expiry, or {@code null}
 * @param createdAt when it was created
 */
public record AlertResponse(
        UUID id,
        UUID watchlistId,
        UUID pairId,
        String symbol,
        MarketType market,
        AlertType type,
        AlertIndicator indicator,
        String timeframe,
        ConditionOperator condition,
        BigDecimal threshold,
        TriggerMode triggerMode,
        Integer cooldownMinutes,
        boolean notifyInApp,
        boolean notifyEmail,
        boolean notifyPush,
        AlertStatus status,
        int triggerCount,
        Instant lastTriggeredAt,
        Instant expiresAt,
        Instant createdAt) {}
