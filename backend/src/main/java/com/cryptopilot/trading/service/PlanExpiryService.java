package com.cryptopilot.trading.service;

/**
 * Expires the ACTIVE plans whose expiry has come before their entry filled (NSF-09). Implemented by
 * {@link com.cryptopilot.trading.service.impl.PlanExpiryServiceImpl}.
 *
 * <p>Rule: NSF-09, BR-32; D-79.
 */
public interface PlanExpiryService {

    /**
     * Expires up to {@code batchSize} due plans, the oldest expiry first, leaving every plan of a pair whose matching
     * replay is running (D-79): the replay may still fill it from a candle before its expiry.
     *
     * @return how many plans were expired
     */
    int expireDue(int batchSize);
}
