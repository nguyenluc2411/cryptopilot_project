package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.paper.dto.response.FillResponse;
import com.cryptopilot.paper.dto.response.FuturesOrderResponse;
import com.cryptopilot.paper.dto.response.FuturesTradeResponse;
import com.cryptopilot.paper.dto.response.OrderResponse;
import com.cryptopilot.paper.entity.PaperFill;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.model.enums.Liquidity;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.TimeInForce;
import java.math.BigDecimal;

/**
 * The bodies an order and a fill are shown in, the same whether a Trader reads them over REST or receives them on the
 * user queues (TR-07, TR-08), so the two never disagree on a field; and a Futures order and fill as the exchange's
 * USDⓈ-M API shows them (TR-15, TR-16).
 *
 * <p>Rule: TR-07, TR-08, TR-15, TR-16.
 */
final class PaperResponses {

    private PaperResponses() {}

    static OrderResponse order(PaperOrder order, String symbol) {
        return new OrderResponse(
                order.getId(),
                order.getClientOrderId(),
                order.getPairId(),
                symbol,
                order.getMarketType(),
                order.getSide(),
                order.getOrderType(),
                order.getTimeInForce(),
                order.getLimitPrice(),
                order.getOrigQuantity(),
                amountOrNull(order.getQuoteOrderAmount()),
                order.getExecutedQuantity(),
                Rounding.toAmount(order.getCumQuoteAmount()),
                order.getAvgPrice(),
                order.getOrderStatus(),
                order.getStatusReason(),
                order.getCreatedAt(),
                order.getClosedAt(),
                order.getStopPrice(),
                order.trailingDelta(),
                order.getTriggeredAt(),
                order.getListId());
    }

    /**
     * @param symbol the pair's symbol
     * @param feeAsset the symbol of the coin the fee was taken in
     */
    static FillResponse fill(PaperFill fill, String symbol, String feeAsset) {
        return new FillResponse(
                fill.getId(),
                fill.getOrderId(),
                fill.getPairId(),
                symbol,
                fill.getMarketType(),
                fill.getSide(),
                fill.getFillPrice(),
                fill.getFillQuantity(),
                Rounding.toAmount(fill.getQuoteAmount()),
                Rounding.toAmount(fill.getFeeAmount()),
                feeAsset,
                fill.getLiquidity(),
                fill.getFillSource(),
                fill.getTradedAt());
    }

    private static BigDecimal amountOrNull(BigDecimal amount) {
        return amount == null ? null : Rounding.toAmount(amount);
    }

    /** A Futures order of the pair named {@code symbol}, as the exchange shows it (TR-15). */
    static FuturesOrderResponse futuresOrder(PaperOrder order, String symbol) {
        return new FuturesOrderResponse(
                order.getId(),
                order.getClientOrderId(),
                symbol,
                order.getOrderStatus(),
                order.getStatusReason(),
                order.getOrderType(),
                order.getOrderType(),
                order.getSide(),
                order.getPositionSide(),
                order.getTimeInForce() != null ? order.getTimeInForce() : TimeInForce.GTC,
                order.getLimitPrice() != null ? order.getLimitPrice() : BigDecimal.ZERO,
                order.getAvgPrice() != null ? order.getAvgPrice() : BigDecimal.ZERO,
                order.getOrigQuantity(),
                order.getExecutedQuantity(),
                order.getCumQuoteAmount(),
                order.isReduceOnly(),
                order.isClosePosition(),
                BigDecimal.ZERO,
                "CONTRACT_PRICE",
                false,
                "NONE",
                "NONE",
                0,
                order.getCreatedAt(),
                order.getClosedAt() != null ? order.getClosedAt() : order.getUpdatedAt());
    }

    /** A Futures fill of the pair named {@code symbol}, its fee taken in {@code asset} (TR-16). */
    static FuturesTradeResponse futuresTrade(PaperFill fill, String symbol, String asset) {
        return new FuturesTradeResponse(
                fill.getId(),
                fill.getOrderId(),
                symbol,
                fill.getSide(),
                fill.getPositionSide(),
                fill.getFillPrice(),
                fill.getFillQuantity(),
                fill.getQuoteAmount(),
                fill.getRealizedPnlAmount(),
                fill.getFeeAmount(),
                asset,
                fill.getLiquidity() == Liquidity.MAKER,
                fill.getSide() == OrderSide.BUY,
                fill.getPositionId(),
                fill.getTradedAt());
    }
}
