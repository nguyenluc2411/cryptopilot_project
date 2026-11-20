package com.cryptopilot.market.dto.response;

import com.cryptopilot.market.model.enums.IncidentKind;
import com.cryptopilot.market.model.enums.IncidentReason;
import com.cryptopilot.market.model.enums.MarketType;
import java.time.Instant;
import java.util.UUID;

/**
 * One period during which trading on a market was closed, for the administration console (D-159).
 *
 * @param id the incident
 * @param market the market closed
 * @param kind an outage or maintenance
 * @param reason what closed it
 * @param lastLiveAt when the market's data was last live before it
 * @param pausedAt when trading closed
 * @param recoveryStartedAt when the data came back and the replay began; {@code null} before
 * @param resumedAt when trading reopened; {@code null} while it is open
 * @param message the administrator's message, if any
 * @param createdBy the administrator who started maintenance, if any
 * @param expectedEndAt when the administrator expected the maintenance to end, if said
 */
public record MarketIncidentResponse(
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
        Instant expectedEndAt) {}
