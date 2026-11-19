package com.cryptopilot.paper.event;

import com.cryptopilot.market.model.enums.MarketType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A Trader with paper orders or positions on a market is to be told of an incident on it (D-158): that trading is
 * paused, or that it reopened and what the catch-up did to their orders and positions.
 *
 * @param userId the Trader
 * @param market the market
 * @param incidentId the incident
 * @param kind paused or resumed
 * @param lastLiveAt when the market's data was last live before it
 * @param resumedAt when trading reopened; {@code null} for a pause
 * @param message the administrator's message of a maintenance, if any
 * @param replayedFills how many of the Trader's fills the catch-up applied; zero for a pause
 * @param liquidations how many of the Trader's positions the catch-up liquidated; zero for a pause
 */
public record PaperIncidentNotice(
        UUID userId,
        MarketType market,
        UUID incidentId,
        Kind kind,
        Instant lastLiveAt,
        Instant resumedAt,
        String message,
        long replayedFills,
        long liquidations) {

    public PaperIncidentNotice {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(lastLiveAt, "lastLiveAt");
    }

    /** What the notice is about. */
    public enum Kind {
        PAUSED,
        RESUMED
    }
}
