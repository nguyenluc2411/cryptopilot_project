package com.cryptopilot.market.event;

import com.cryptopilot.market.model.enums.IncidentReason;
import com.cryptopilot.market.model.enums.MarketHealthState;
import com.cryptopilot.market.model.enums.MarketType;
import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * An incident has closed trading on a market long enough for the Traders to be told (D-158): {@code notify-after} of
 * an outage, at once for maintenance. Published once per incident; a stream that comes back within
 * {@code notify-after} is never announced, so a short blip writes no notice.
 *
 * <p>Exposed in the named interface {@code events}: the paper and plan modules tell the Traders they affect.
 *
 * @param market the market closed
 * @param incidentId its incident
 * @param state its state when announced
 * @param reason what closed it
 * @param lastLiveAt when its data was last live
 * @param message the administrator's message of a maintenance, if any
 */
@NamedInterface("events")
public record MarketPauseAnnounced(
        MarketType market,
        UUID incidentId,
        MarketHealthState state,
        IncidentReason reason,
        Instant lastLiveAt,
        String message) {}
