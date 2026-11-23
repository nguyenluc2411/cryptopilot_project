package com.cryptopilot.paper.event;

import com.cryptopilot.market.model.enums.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A paper order ended in a way its Trader is told about even when not watching the trading screen (TR-29, plan 9):
 * it filled, its conditional order triggered, or the system cancelled or expired it. A cancel of the Trader's own, an
 * order refused or ended on arrival, an OCO leg cancelled because the other one executed, and an order of a trading
 * plan (whose own notifications tell of it) are not notices. Published inside the transaction that ended it, so the
 * notification is written only once it has committed.
 *
 * <p>Rule: TR-29; NSF-15; D-86.
 *
 * @param userId the Trader
 * @param orderId the order
 * @param market SPOT or FUTURES
 * @param symbol the pair, e.g. {@code BTCUSDT}
 * @param side {@code BUY} or {@code SELL}
 * @param orderType the order's type, e.g. {@code LIMIT} or {@code STOP_MARKET}
 * @param kind what happened
 * @param reason the status reason the order history shows for a cancel or an expiry; otherwise {@code null}
 * @param executedQuantity how much it filled
 * @param avgPrice its average fill price, or {@code null} when nothing filled
 * @param at when it ended
 */
public record PaperOrderNotice(
        UUID userId,
        UUID orderId,
        MarketType market,
        String symbol,
        String side,
        String orderType,
        Kind kind,
        String reason,
        BigDecimal executedQuantity,
        BigDecimal avgPrice,
        Instant at) {

    public PaperOrderNotice {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(orderType, "orderType");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(executedQuantity, "executedQuantity");
        Objects.requireNonNull(at, "at");
    }

    /** What happened to the order. */
    public enum Kind {
        /** Filled in full. */
        FILLED,
        /** A conditional order reached its trigger and placed the order it carries. */
        TRIGGERED,
        /** Cancelled by the system, e.g. its pair stopped trading or a liquidation. */
        CANCELED,
        /** Expired by the system, e.g. its good-till date passed. */
        EXPIRED
    }
}
