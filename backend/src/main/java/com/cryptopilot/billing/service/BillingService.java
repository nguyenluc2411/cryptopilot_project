package com.cryptopilot.billing.service;

import com.cryptopilot.billing.dto.CreateOrderRequest;
import com.cryptopilot.billing.dto.CreateOrderResponse;
import com.cryptopilot.billing.dto.GatewayResponse;
import com.cryptopilot.billing.dto.OrderResponse;
import com.cryptopilot.billing.dto.PackageResponse;
import com.cryptopilot.billing.dto.QuoteRequest;
import com.cryptopilot.billing.dto.QuoteResponse;
import com.cryptopilot.billing.dto.SubscriptionResponse;
import com.cryptopilot.common.web.PageResponse;
import java.util.List;
import java.util.UUID;

/**
 * The Trader side of subscriptions and payment: the packages on sale, the gateways open, a quote, an order and its
 * status, and the plan held (SCR-30, SCR-31, SCR-32, SCR-07).
 *
 * <p>Rule: BR-53…BR-56, BR-63 as amended by D-146; SRS 3.10; TECHNICAL_DESIGN 7.11.
 */
public interface BillingService {

    /** FREE, for comparison, then every paid package on sale, lowest tier first. */
    List<PackageResponse> packages();

    /** Every gateway a new order may name, and whether it is open now. */
    List<GatewayResponse> gateways();

    /** What buying the package would do if it were paid now. */
    QuoteResponse quote(UUID userId, QuoteRequest request);

    /** A PENDING order for the package, and how the browser reaches the gateway's payment page. */
    CreateOrderResponse createOrder(UUID userId, CreateOrderRequest request);

    /** The Trader's orders, newest first. */
    PageResponse<OrderResponse> orders(UUID userId, Integer page, Integer pageSize);

    /** One of the Trader's orders, by code. */
    OrderResponse order(UUID userId, String orderCode);

    /** The plan the Trader holds now. */
    SubscriptionResponse subscription(UUID userId);
}
