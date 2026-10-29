package com.cryptopilot.paper.dto.response;

import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.OrderType;
import com.cryptopilot.paper.model.enums.PositionSide;
import com.cryptopilot.paper.model.enums.TimeInForce;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One paper Futures conditional order, with the exchange's field names. Binance: the algo order of
 * {@code POST /fapi/v1/algoOrder}, {@code GET /fapi/v1/algoOrder}, {@code openAlgoOrders} and {@code allAlgoOrders}.
 * {@code statusReason} is CryptoPilot's, saying why an order was rejected or expired at its trigger.
 *
 * @param algoId the conditional order
 * @param clientAlgoId the idempotency key it was placed with, or the one generated
 * @param algoType {@code CONDITIONAL}
 * @param orderType STOP, STOP_MARKET, TAKE_PROFIT or TAKE_PROFIT_MARKET
 * @param symbol the pair
 * @param side BUY or SELL
 * @param positionSide BOTH in one-way mode
 * @param timeInForce STOP and TAKE_PROFIT only; {@code null} otherwise
 * @param quantity the quantity asked for; {@code null} with {@code closePosition}
 * @param algoStatus NEW (waiting), TRIGGERED (the order it placed works), FINISHED (that order ended), CANCELED,
 *     EXPIRED or REJECTED
 * @param statusReason why it expired or was rejected, e.g. {@code MARGIN_INSUFFICIENT}; otherwise {@code null}
 * @param actualOrderId the order it placed once triggered; {@code null} before
 * @param actualPrice the average price that order executed at; zero before anything did
 * @param triggerPrice the price it triggers at
 * @param price the limit price of STOP and TAKE_PROFIT; zero otherwise
 * @param workingType {@code CONTRACT_PRICE} or {@code MARK_PRICE}
 * @param priceMatch always NONE
 * @param closePosition whether it closes the whole position
 * @param priceProtect whether it waits while the mark and the last price are too far apart
 * @param reduceOnly whether it may only make the position smaller
 * @param createTime when it was placed
 * @param updateTime when it last changed
 * @param triggerTime when it triggered; {@code null} before
 */
public record FuturesAlgoOrderResponse(
        UUID algoId,
        String clientAlgoId,
        String algoType,
        OrderType orderType,
        String symbol,
        OrderSide side,
        PositionSide positionSide,
        TimeInForce timeInForce,
        BigDecimal quantity,
        String algoStatus,
        String statusReason,
        UUID actualOrderId,
        BigDecimal actualPrice,
        BigDecimal triggerPrice,
        BigDecimal price,
        String workingType,
        String priceMatch,
        boolean closePosition,
        boolean priceProtect,
        boolean reduceOnly,
        Instant createTime,
        Instant updateTime,
        Instant triggerTime) {}
