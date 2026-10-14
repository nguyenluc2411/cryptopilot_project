package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.paper.dto.response.FillResponse;
import com.cryptopilot.paper.dto.response.OrderResponse;
import com.cryptopilot.paper.entity.PaperFill;
import com.cryptopilot.paper.entity.PaperOrder;
import java.math.BigDecimal;

/**
 * The bodies an order and a fill are shown in, the same whether a Trader reads them over REST or receives them on the
 * user queues (TR-07, TR-08), so the two never disagree on a field.
 *
 * <p>Rule: TR-07, TR-08.
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
                order.getClosedAt());
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
}
