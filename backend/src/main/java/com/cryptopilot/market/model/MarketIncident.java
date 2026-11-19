package com.cryptopilot.market.model;

import com.cryptopilot.market.model.enums.IncidentKind;
import com.cryptopilot.market.model.enums.IncidentReason;
import com.cryptopilot.market.model.enums.MarketType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One period during which trading on a market was closed (D-158), a row of {@code market_incident}.
 *
 * @param id the incident
 * @param market the market closed
 * @param kind an outage or maintenance
 * @param reason what closed it
 * @param lastLiveAt when the market's last live data arrived, where a replay starts from
 * @param pausedAt when trading closed
 * @param recoveryStartedAt when the data came back and the replay began; {@code null} before, or after a relapse
 * @param resumedAt when trading reopened; {@code null} while the incident is open
 * @param message the administrator's message for the Traders, if any
 * @param createdBy the administrator who started maintenance, if any
 */
public record MarketIncident(
        UUID id,
        MarketType market,
        IncidentKind kind,
        IncidentReason reason,
        Instant lastLiveAt,
        Instant pausedAt,
        Instant recoveryStartedAt,
        Instant resumedAt,
        String message,
        UUID createdBy) {

    public MarketIncident {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(lastLiveAt, "lastLiveAt");
        Objects.requireNonNull(pausedAt, "pausedAt");
    }

    /** Whether trading on the market is still closed by this incident. */
    public boolean open() {
        return resumedAt == null;
    }
}
