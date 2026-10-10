package com.cryptopilot.watchlist.service;

/**
 * Ends alerts whose expiry has passed (BR-19). Implemented by
 * {@link com.cryptopilot.watchlist.service.impl.AlertExpiryServiceImpl}.
 *
 * <p>Rule: BR-19; SRS 3.4.4.
 */
public interface AlertExpiryService {

    /**
     * Sets every ACTIVE or PAUSED alert past its expiry EXPIRED; a TRIGGERED alert is left as it is.
     *
     * @return how many alerts expired
     */
    int expireDue();
}
