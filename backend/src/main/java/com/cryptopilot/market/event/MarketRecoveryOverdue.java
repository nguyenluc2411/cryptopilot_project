package com.cryptopilot.market.event;

import com.cryptopilot.market.model.enums.MarketType;
import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * A market's recovery needs an administrator (D-159): it has run longer than {@code recovery-timeout}, or its data was
 * lost longer than {@code max-outage-replay} so it cannot all be replayed and the market does not reopen by itself.
 * Published once per incident; trading stays closed.
 *
 * <p>Exposed in the named interface {@code events}.
 *
 * @param market the market recovering
 * @param incidentId its incident
 * @param lastLiveAt when its data was last live before the incident
 * @param recoveringSince when the current recovery began
 * @param beyondReplay whether the data was lost longer than can be replayed, so only an administrator reopens it
 */
@NamedInterface("events")
public record MarketRecoveryOverdue(
        MarketType market, UUID incidentId, Instant lastLiveAt, Instant recoveringSince, boolean beyondReplay) {}
