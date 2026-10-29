package com.cryptopilot.paper.dto.response;

import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.OrderStatus;
import com.cryptopilot.paper.model.enums.OrderType;
import com.cryptopilot.paper.model.enums.PositionSide;
import com.cryptopilot.paper.model.enums.TimeInForce;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One paper Futures order, with the exchange's field names. Binance: the order of {@code POST /fapi/v1/order},
 * {@code GET /fapi/v1/order}, {@code openOrders} and {@code allOrders}. Ids are UUIDs and times ISO-8601, as
 * elsewhere in this API; {@code statusReason} is CryptoPilot's, saying why an order ended other than by filling.
 *
 * @param orderId the order
 * @param clientOrderId the idempotency key it was placed with, or the one generated
 * @param symbol the pair
 * @param status NEW, PARTIALLY_FILLED, FILLED, CANCELED or EXPIRED
 * @param statusReason why it ended other than by filling, e.g. {@code MARGIN_INSUFFICIENT}; otherwise {@code null}
 * @param type LIMIT or MARKET
 * @param origType the type it was placed as, the same
 * @param side BUY or SELL
 * @param positionSide BOTH in one-way mode
 * @param timeInForce a LIMIT order's; GTC for a MARKET one, as the exchange shows it
 * @param price the limit price, zero for MARKET
 * @param avgPrice the average price of what executed, zero before anything did
 * @param origQty the quantity asked for
 * @param executedQty the quantity executed
 * @param cumQuote the value of what executed, in USDT
 * @param reduceOnly whether it may only make the position smaller
 * @param closePosition always false for these types
 * @param stopPrice always zero for these types
 * @param workingType always CONTRACT_PRICE for these types
 * @param priceProtect always false for these types
 * @param priceMatch always NONE
 * @param selfTradePreventionMode always NONE: demo orders never meet each other
 * @param goodTillDate always zero (GTD is not offered yet)
 * @param time when it was placed
 * @param updateTime when it last changed
 */
public record FuturesOrderResponse(
        UUID orderId,
        String clientOrderId,
        String symbol,
        OrderStatus status,
        String statusReason,
        OrderType type,
        OrderType origType,
        OrderSide side,
        PositionSide positionSide,
        TimeInForce timeInForce,
        BigDecimal price,
        BigDecimal avgPrice,
        BigDecimal origQty,
        BigDecimal executedQty,
        BigDecimal cumQuote,
        boolean reduceOnly,
        boolean closePosition,
        BigDecimal stopPrice,
        String workingType,
        boolean priceProtect,
        String priceMatch,
        String selfTradePreventionMode,
        long goodTillDate,
        Instant time,
        Instant updateTime) {}
