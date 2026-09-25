package com.cryptopilot.market.service;

import com.cryptopilot.market.client.BinanceClientException;
import com.cryptopilot.market.model.MetricsRun;
import com.cryptopilot.market.model.SettlementRun;

/**
 * The use cases of {@link com.cryptopilot.market.service.impl.FuturesMetricsServiceImpl}: the methods called from outside it (D-48).
 *
 * <p>Rule: NSF-04; BR-07, BR-09, BR-10, BR-11, BR-37; TECHNICAL_DESIGN 7.1 step 8 and 7.1.2.
 */
public interface FuturesMetricsService {

    /**
     * Collects the open interest and long/short account ratio of every target pair up to the last period before
     * the current minute, or until the run's request limit.
     *
     * @throws BinanceClientException for a refusal other than {@code REJECTED} and {@code MALFORMED}; what was
     *     written before it stays written
     */
    MetricsRun collectMetrics();

    /**
     * Stores the settled funding rates every target pair has not stored yet.
     *
     * @throws BinanceClientException for a refusal other than {@code REJECTED} and {@code MALFORMED}; what was
     *     stored before it stays stored
     */
    SettlementRun settleFunding();
}
