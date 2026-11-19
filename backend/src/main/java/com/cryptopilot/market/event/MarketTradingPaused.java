package com.cryptopilot.market.event;

import com.cryptopilot.market.model.enums.IncidentReason;
import com.cryptopilot.market.model.enums.MarketHealthState;
import com.cryptopilot.market.model.enums.MarketType;
import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * Trading on a market has just closed (D-158): its data was lost or an administrator started maintenance. Published
 * once per incident, not again on a relapse while recovering.
 *
 * <p>Exposed in the named interface {@code events}: the Traders' notices and the banner hear it.
 *
 * @param market the market closed
 * @param incidentId its incident
 * @param state {@code PAUSED} or {@code MAINTENANCE}
 * @param reason what closed it
 * @param lastLiveAt when its data was last live
 */
@NamedInterface("events")
public record MarketTradingPaused(
        MarketType market, UUID incidentId, MarketHealthState state, IncidentReason reason, Instant lastLiveAt) {}
