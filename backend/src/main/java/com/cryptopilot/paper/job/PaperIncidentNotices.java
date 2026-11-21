package com.cryptopilot.paper.job;

import com.cryptopilot.market.event.MarketPauseAnnounced;
import com.cryptopilot.market.event.MarketTradingResumed;
import com.cryptopilot.market.model.enums.IncidentReason;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.event.PaperIncidentNotice;
import com.cryptopilot.paper.service.PaperIncidentService;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Tells the Traders with paper orders or positions on a market of an incident on it (D-159), one notice per Trader:
 * when the pause is announced, and when trading reopens, with how many of their fills and liquidations the catch-up
 * applied. A pause never announced (shorter than {@code notify-after}) gets a reopening notice only for the Traders
 * the catch-up changed something for. Orchestration only.
 *
 * <p>Rule: NSF-03; D-159.
 */
@Component
public class PaperIncidentNotices {

    private final PaperIncidentService queries;
    private final ApplicationEventPublisher events;

    public PaperIncidentNotices(PaperIncidentService queries, ApplicationEventPublisher events) {
        this.queries = queries;
        this.events = events;
    }

    @ApplicationModuleListener
    public void on(MarketPauseAnnounced announced) {
        for (UUID trader : queries.tradersOn(announced.market())) {
            events.publishEvent(new PaperIncidentNotice(
                    trader,
                    announced.market(),
                    announced.incidentId(),
                    PaperIncidentNotice.Kind.PAUSED,
                    announced.lastLiveAt(),
                    null,
                    announced.message(),
                    announced.reason() == IncidentReason.MAINTENANCE,
                    0,
                    0));
        }
    }

    @ApplicationModuleListener
    public void on(MarketTradingResumed resumed) {
        MarketType market = resumed.market();
        Map<UUID, Long> fills = queries.replayedFills(market, resumed.lastLiveAt(), resumed.resumedAt());
        Map<UUID, Long> liquidations = market == MarketType.FUTURES
                ? queries.liquidations(resumed.lastLiveAt(), resumed.resumedAt())
                : Map.of();
        Set<UUID> told = new LinkedHashSet<>(fills.keySet());
        told.addAll(liquidations.keySet());
        if (resumed.announced()) {
            told.addAll(queries.tradersOn(market));
        }
        for (UUID trader : told) {
            events.publishEvent(new PaperIncidentNotice(
                    trader,
                    market,
                    resumed.incidentId(),
                    PaperIncidentNotice.Kind.RESUMED,
                    resumed.lastLiveAt(),
                    resumed.resumedAt(),
                    null,
                    false,
                    fills.getOrDefault(trader, 0L),
                    liquidations.getOrDefault(trader, 0L)));
        }
    }
}
