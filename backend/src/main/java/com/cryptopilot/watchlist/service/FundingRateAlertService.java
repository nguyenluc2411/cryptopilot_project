package com.cryptopilot.watchlist.service;

/**
 * NSF-06 for FUNDING_RATE alerts: evaluates the current predicted funding rate of each Futures pair that has ACTIVE
 * FUNDING_RATE alerts. Implemented by {@link com.cryptopilot.watchlist.service.impl.FundingRateAlertServiceImpl}.
 *
 * <p>Rule: NSF-06, BR-19, BR-20; SRS 3.4.4.
 */
public interface FundingRateAlertService {

    /**
     * Reads each pair's rate once and records the triggers the database allows.
     *
     * @return how many triggers this call recorded
     */
    int evaluate();
}
