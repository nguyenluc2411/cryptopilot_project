package com.cryptopilot.trading.job;

import com.cryptopilot.market.PairMarketDisabled;
import com.cryptopilot.trading.service.TradingPlanService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Cancels the ACTIVE plans of a pair's market when an administrator takes it out of service (BR-59). Listens
 * synchronously, inside the transaction that disabled the market, so the pair is never disabled with plans still
 * waiting on it: a failure here rolls the administrator's change back. Orchestration only.
 *
 * <p>Rule: BR-59; UC-45.
 */
@Component
public class DisabledMarketListener {

    private final TradingPlanService plans;

    public DisabledMarketListener(TradingPlanService plans) {
        this.plans = plans;
    }

    @EventListener
    public void on(PairMarketDisabled disabled) {
        plans.cancelForDisabledMarket(disabled.pairId(), disabled.symbol(), disabled.market(), disabled.disabledAt());
    }
}
