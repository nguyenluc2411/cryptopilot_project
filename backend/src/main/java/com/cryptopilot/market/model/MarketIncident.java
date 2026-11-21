package com.cryptopilot.market.model;

import com.cryptopilot.market.model.enums.IncidentKind;
import com.cryptopilot.market.model.enums.IncidentReason;
import com.cryptopilot.market.model.enums.MarketType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One period during which trading on a market was closed (D-159), a row of {@code market_incident}.
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
 * @param expectedEndAt when the administrator expects a maintenance to end, if said
 * @param version the row's version, bumped by every update: a write names the one it read
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
        UUID createdBy,
        Instant expectedEndAt,
        int version) {

    /** A new incident, not yet updated. */
    public MarketIncident(
            UUID id,
            MarketType market,
            IncidentKind kind,
            IncidentReason reason,
            Instant lastLiveAt,
            Instant pausedAt,
            Instant recoveryStartedAt,
            Instant resumedAt,
            String message,
            UUID createdBy,
            Instant expectedEndAt) {
        this(
                id,
                market,
                kind,
                reason,
                lastLiveAt,
                pausedAt,
                recoveryStartedAt,
                resumedAt,
                message,
                createdBy,
                expectedEndAt,
                0);
    }

    /** An incident with no expected end. */
    public MarketIncident(
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
        this(id, market, kind, reason, lastLiveAt, pausedAt, recoveryStartedAt, resumedAt, message, createdBy, null);
    }

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
