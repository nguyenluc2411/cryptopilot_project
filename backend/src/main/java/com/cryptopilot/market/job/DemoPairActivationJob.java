package com.cryptopilot.market.job;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.config.DemoPairProperties;
import com.cryptopilot.market.config.SymbolSyncProperties;
import com.cryptopilot.market.event.SymbolsSynchronised;
import com.cryptopilot.market.service.DemoPairActivation;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * In the {@code dev} profile, activates the demo pairs at start-up (Q-16): once per market, right after the first
 * symbol synchronisation of that market — the first moment the exchange's statuses are known — and before the
 * other listeners of that event, so the stream supervisor already sees the pairs it re-reads.
 *
 * <p>Only once per market and process: a demo pair an administrator later turns off stays off until the next
 * start. Off by default and in {@code prod} (BR-07).
 *
 * <p>Rule: BR-07; NSF-01, NSF-03; Q-16.
 */
@Component
public class DemoPairActivationJob {

    private static final Logger log = LoggerFactory.getLogger(DemoPairActivationJob.class);

    private final DemoPairActivation activation;
    private final DemoPairProperties properties;
    private final SymbolSyncProperties sync;
    private final Set<MarketType> done = EnumSet.noneOf(MarketType.class);

    public DemoPairActivationJob(
            DemoPairActivation activation, DemoPairProperties properties, SymbolSyncProperties sync) {
        this.activation = activation;
        this.properties = properties;
        this.sync = sync;
    }

    /** The first synchronisation of a market since start-up: activate its demo pairs, when configured. */
    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public synchronized void onSymbolsSynchronised(SymbolsSynchronised event) {
        if (!properties.activateSeedSymbols() || !done.add(event.market())) {
            return;
        }
        try {
            List<String> activated = activation.activate(event.market(), sync.seedSymbols());
            log.info("Q-16 demo pairs activated on {}: {}", event.market(), activated);
        } catch (RuntimeException failure) {
            done.remove(event.market());
            log.error(
                    "Q-16 demo pairs could not be activated on {}; tried again after the next sync",
                    event.market(),
                    failure);
        }
    }
}
