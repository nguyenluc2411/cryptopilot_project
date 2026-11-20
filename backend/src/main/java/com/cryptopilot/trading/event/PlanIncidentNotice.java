package com.cryptopilot.trading.event;

import com.cryptopilot.market.model.enums.MarketType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A Trader with a waiting plan or an open plan position on a market is to be told of an incident on it (D-159): that
 * the plans are paused, or that they are watched again. What a catch-up filled or closed is told by the plans' own
 * notices.
 *
 * @param userId the Trader
 * @param market the market
 * @param incidentId the incident
 * @param resumed {@code false} for the pause, {@code true} once trading reopened
 * @param lastLiveAt when the market's data was last live before it
 * @param resumedAt when trading reopened; {@code null} for the pause
 * @param message the administrator's message of a maintenance, if any
 * @param maintenance whether the pause is a maintenance, with or without a message; {@code false} once resumed
 */
public record PlanIncidentNotice(
        UUID userId,
        MarketType market,
        UUID incidentId,
        boolean resumed,
        Instant lastLiveAt,
        Instant resumedAt,
        String message,
        boolean maintenance) {

    public PlanIncidentNotice {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(lastLiveAt, "lastLiveAt");
    }
}
