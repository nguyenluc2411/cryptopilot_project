package com.cryptopilot.market.event;

import com.cryptopilot.market.model.enums.ExchangeStatus;
import java.util.Objects;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * The symbol synchronisation found that the exchange no longer trades a pair on Spot: its status left TRADING, or it
 * no longer appears at all (NSF-01). Published inside the synchronisation's transaction, so the event publication
 * registry records it with the new status and delivers it once that commits, again after a failure (ADR-010). It may
 * arrive more than once; listeners must be idempotent.
 *
 * <p>Exposed in the named interface {@code events}: the paper trading module cancels the open orders of the pair
 * (TR-02, paper trading plan §6.2).
 *
 * <p>Rule: NSF-01; TR-02; ADR-010.
 *
 * @param pairId the pair
 * @param symbol the pair's symbol
 * @param status the exchange status it moved to: NOT_TRADING or DELISTED
 */
@NamedInterface("events")
public record SpotPairStoppedTrading(UUID pairId, String symbol, ExchangeStatus status) {

    public SpotPairStoppedTrading {
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(status, "status");
    }
}
