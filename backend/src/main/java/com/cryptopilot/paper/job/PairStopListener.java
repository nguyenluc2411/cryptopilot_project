package com.cryptopilot.paper.job;

import com.cryptopilot.market.PairMarketDisabled;
import com.cryptopilot.market.event.SpotPairStoppedTrading;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.service.impl.PairStopCancellation;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

/**
 * Cancels the paper orders of a pair the system no longer trades on Spot (TR-02, paper trading plan §6.2): the exchange
 * stopped trading it, or an administrator took its Spot market out of service (BR-07, UC-45), after which no stream
 * feeds it and an order would wait with its funds locked. The events reach this class through the event publication
 * registry (ADR-010): recorded with the change that published them, delivered once it commits, and delivered again
 * while a delivery fails.
 *
 * <p>One account at a time, each in its own transaction ({@link PairStopCancellation}): an account that fails does not
 * stop the others, and the delivery then fails so the outbox delivers it again. A delivery run again cancels nothing
 * twice, as an account whose orders are cancelled has none left open.
 *
 * <p>Rule: TR-02; NSF-01; BR-07, UC-45; ADR-010.
 *
 * <p>Reference: Richardson, C. (2018). <i>Microservices Patterns</i>. Manning, ch. 3 (Transactional Outbox; idempotent
 * consumers).
 */
@Component
public class PairStopListener {

    private static final Logger log = LoggerFactory.getLogger(PairStopListener.class);

    private final PairStopCancellation cancellation;

    public PairStopListener(PairStopCancellation cancellation) {
        this.cancellation = cancellation;
    }

    // No transaction of its own: each account takes one.
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(SpotPairStoppedTrading stopped) {
        cancelAll(stopped.pairId(), stopped.symbol());
    }

    // Paper orders are Spot only, so a Futures market taken out of service leaves them as they are.
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(PairMarketDisabled disabled) {
        if (disabled.market() == MarketType.SPOT) {
            cancelAll(disabled.pairId(), disabled.symbol());
        }
    }

    private void cancelAll(UUID pairId, String symbol) {
        int failed = 0;
        for (UUID accountId : cancellation.accountsWithOrders(pairId)) {
            try {
                cancellation.cancel(accountId, pairId);
            } catch (RuntimeException failure) {
                failed++;
                log.warn(
                        "TR-02 paper orders of account {} on {} not cancelled; the delivery is tried again: {}",
                        accountId,
                        symbol,
                        failure.toString());
            }
        }
        if (failed > 0) {
            throw new IllegalStateException(failed + " accounts with paper orders on " + symbol + " not cancelled yet");
        }
    }
}
