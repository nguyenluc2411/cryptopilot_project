package com.cryptopilot.watchlist.job;

import com.cryptopilot.billing.event.PlanDowngraded;
import com.cryptopilot.watchlist.service.AlertPlanService;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Applies BR-64 to the Trader's alerts once the billing module has committed a plan drop; a failure is retried from
 * the event publication log like any other module listener.
 *
 * <p>Rule: BR-64.
 */
@Component
public class PlanDowngradeListener {

    private final AlertPlanService plans;

    public PlanDowngradeListener(AlertPlanService plans) {
        this.plans = plans;
    }

    @ApplicationModuleListener
    public void on(PlanDowngraded downgrade) {
        plans.applyPlan(downgrade);
    }
}
