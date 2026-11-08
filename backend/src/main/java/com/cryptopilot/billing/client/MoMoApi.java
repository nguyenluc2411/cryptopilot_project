package com.cryptopilot.billing.client;

import java.util.Map;

/**
 * The two MoMo v2 calls the gateway makes, {@code POST /v2/gateway/api/create} and {@code POST /v2/gateway/api/query},
 * as JSON in and JSON text out, read by {@link MoMoGateway}. Its own type so the protocol is tested without a network.
 *
 * <p>Rule: D-146.
 */
public interface MoMoApi {

    /**
     * Posts the signed request to the path and returns the JSON MoMo answers.
     *
     * @throws GatewayUnavailableException when MoMo cannot be reached or answers with an HTTP error
     */
    String post(String path, Map<String, Object> request);
}
