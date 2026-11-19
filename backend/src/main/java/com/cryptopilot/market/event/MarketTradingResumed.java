package com.cryptopilot.market.event;

import com.cryptopilot.market.model.enums.MarketType;
import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * Trading on a market has reopened after an incident (D-158): everything was replayed and caught up. Published once
 * per incident.
 *
 * <p>Exposed in the named interface {@code events}: the Traders' summaries hear it.
 *
 * @param market the market reopened
 * @param incidentId the incident that ended
 * @param lastLiveAt when its data was last live before the incident
 * @param resumedAt when trading reopened
 * @param announced whether {@link MarketPauseAnnounced} was published for the incident
 */
@NamedInterface("events")
public record MarketTradingResumed(
        MarketType market, UUID incidentId, Instant lastLiveAt, Instant resumedAt, boolean announced) {}
