package com.cryptopilot.market.event;

import com.cryptopilot.market.model.enums.MarketType;
import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * A closed market's data flows again and what was missed is being replayed (D-158); trading stays closed. May be
 * published more than once per incident, after each relapse.
 *
 * <p>Exposed in the named interface {@code events}.
 *
 * @param market the market recovering
 * @param incidentId its incident
 * @param lastLiveAt when its data was last live before the incident, where the replay starts
 */
@NamedInterface("events")
public record MarketRecoveryStarted(MarketType market, UUID incidentId, Instant lastLiveAt) {}
