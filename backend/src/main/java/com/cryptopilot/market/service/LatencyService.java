package com.cryptopilot.market.service;

import com.cryptopilot.market.dto.request.LatencySamplesRequest;
import com.cryptopilot.market.dto.response.LatencyReportResponse;

/**
 * The realtime latency (TR-36): the clients' samples recorded, and the report of every latency timer for the
 * administrator, with the topic subscription counts of TR-34.
 *
 * <p>Rule: TR-36; TR-34.
 */
public interface LatencyService {

    /** Records the client's samples under {@code cryptopilot.client.latency}, by kind. */
    void record(LatencySamplesRequest samples);

    /** Every latency timer's percentiles, and the subscriptions of the market topics. */
    LatencyReportResponse report();
}
