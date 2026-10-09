package com.cryptopilot.paper.dto.response;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.OrderStatus;
import com.cryptopilot.paper.model.enums.OrderType;
import com.cryptopilot.paper.model.enums.TimeInForce;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A paper order of the caller.
 *
 * @param orderId the order
 * @param clientOrderId its idempotency key, as sent or as generated
 * @param pairId the pair
 * @param symbol the pair's symbol, e.g. {@code BTCUSDT}
 * @param market the market
 * @param side BUY or SELL
 * @param type MARKET or LIMIT
 * @param timeInForce a LIMIT order's time in force; otherwise {@code null}
 * @param price a LIMIT order's price; otherwise {@code null}
 * @param quantity the quantity asked for; {@code null} for a MARKET buy by total
 * @param quoteOrderQty what a MARKET buy by total spends; otherwise {@code null}
 * @param executedQuantity the quantity executed so far
 * @param cumQuote what the executions cost or brought, before fees
 * @param avgPrice the average execution price; {@code null} before the first execution
 * @param status where the order stands
 * @param statusReason why an order ended other than by filling, e.g. {@code IOC_NOT_FILLABLE}; otherwise {@code null}
 * @param placedAt when it was placed
 * @param closedAt when it finished; {@code null} while it works
 */
public record OrderResponse(
        UUID orderId,
        String clientOrderId,
        UUID pairId,
        String symbol,
        MarketType market,
        OrderSide side,
        OrderType type,
        TimeInForce timeInForce,
        BigDecimal price,
        BigDecimal quantity,
        BigDecimal quoteOrderQty,
        BigDecimal executedQuantity,
        BigDecimal cumQuote,
        BigDecimal avgPrice,
        OrderStatus status,
        String statusReason,
        Instant placedAt,
        Instant closedAt) {}
