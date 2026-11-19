package com.cryptopilot.trading.job;

import com.cryptopilot.market.event.MarketPauseAnnounced;
import com.cryptopilot.market.event.MarketTradingResumed;
import com.cryptopilot.trading.event.PlanIncidentNotice;
import com.cryptopilot.trading.service.PlanIncidentService;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Tells the Traders with plans waiting or open on a market of an announced incident on it (D-158): when it is
 * announced, and when trading reopens. A pause never announced writes nothing; what its catch-up did to a plan is told
 * by the plan's own notices. Orchestration only.
 *
 * <p>Rule: NSF-03; D-158.
 */
@Component
public class PlanIncidentNotices {

    private final PlanIncidentService queries;
    private final ApplicationEventPublisher events;

    public PlanIncidentNotices(PlanIncidentService queries, ApplicationEventPublisher events) {
        this.queries = queries;
        this.events = events;
    }

    @ApplicationModuleListener
    public void on(MarketPauseAnnounced announced) {
        for (UUID trader : queries.tradersOn(announced.market())) {
            events.publishEvent(new PlanIncidentNotice(
                    trader,
                    announced.market(),
                    announced.incidentId(),
                    false,
                    announced.lastLiveAt(),
                    null,
                    announced.message()));
        }
    }

    @ApplicationModuleListener
    public void on(MarketTradingResumed resumed) {
        if (!resumed.announced()) {
            return;
        }
        for (UUID trader : queries.tradersOn(resumed.market())) {
            events.publishEvent(new PlanIncidentNotice(
                    trader,
                    resumed.market(),
                    resumed.incidentId(),
                    true,
                    resumed.lastLiveAt(),
                    resumed.resumedAt(),
                    null));
        }
    }
}
