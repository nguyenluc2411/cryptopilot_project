package com.cryptopilot.watchlist.service;

import com.cryptopilot.market.event.OpenInterestSampled;

/**
 * NSF-06 for OPEN_INTEREST_CHANGE alerts: evaluates a committed live open interest reading against the ACTIVE alerts
 * of its pair. Implemented by {@link com.cryptopilot.watchlist.service.impl.OpenInterestAlertServiceImpl}.
 *
 * <p>Rule: NSF-06, BR-19, BR-20; SRS 3.4.4; Q-45.
 */
public interface OpenInterestAlertService {

    /**
     * Evaluates the reading and records the triggers the database allows.
     *
     * @return how many triggers this call recorded
     */
    int evaluate(OpenInterestSampled sample);
}
