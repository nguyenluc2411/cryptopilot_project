package com.cryptopilot.paper.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.OrderStatus;
import com.cryptopilot.paper.model.enums.OrderType;
import com.cryptopilot.paper.model.enums.TimeInForce;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

/**
 * One order of a paper account, open or finished, never deleted: the order history of the trading screens.
 *
 * <p>This stage of TR-02 places Spot MARKET and LIMIT orders, which execute whole or not at all: the simulation has
 * no order book depth to fill part of one against. The columns of the later stages (stop and trailing prices, the
 * position side, order lists) are left unmapped until those stages use them; the database fills them with null.
 *
 * <p>What a working order holds back is not stored: it follows from the order, so {@link #lockedAmount()} gives the
 * same figure at placement, at a fill and at a cancel.
 *
 * <p>Rule: TR-02; BR-21 (Spot owns what it sells and holds no position).
 */
@Getter
@Entity
@Table(name = "paper_order")
@AttributeOverride(name = "id", column = @Column(name = "order_id", nullable = false, updatable = false))
public class PaperOrder extends BaseEntity {

    /** Decimals of a price or a quantity column, {@code numeric(28,12)}. */
    public static final int PRICE_SCALE = 12;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "pair_id", nullable = false, updatable = false)
    private UUID pairId;

    @Enumerated(EnumType.STRING)
    @Column(name = "market_type", nullable = false, length = 32, updatable = false)
    private MarketType marketType;

    /** The idempotency key the client sent, or one generated for a request that sent none. */
    @Column(name = "client_order_id", nullable = false, length = 64, updatable = false)
    private String clientOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 32, updatable = false)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 32, updatable = false)
    private OrderType orderType;

    @Enumerated(EnumType.STRING)
    @Column(name = "time_in_force", length = 32, updatable = false)
    private TimeInForce timeInForce;

    @Column(name = "limit_price", precision = 28, scale = 12, updatable = false)
    private BigDecimal limitPrice;

    /** The quantity asked for, or {@code null} for a MARKET buy sized by what it spends. */
    @Column(name = "orig_quantity", precision = 28, scale = 12, updatable = false)
    private BigDecimal origQuantity;

    /** What a MARKET buy "by total" spends, in the quote asset; otherwise {@code null}. */
    @Column(name = "quote_order_amount", precision = 28, scale = 8, updatable = false)
    private BigDecimal quoteOrderAmount;

    @Column(name = "executed_quantity", nullable = false, precision = 28, scale = 12)
    private BigDecimal executedQuantity;

    @Column(name = "cum_quote_amount", nullable = false, precision = 28, scale = 8)
    private BigDecimal cumQuoteAmount;

    @Column(name = "avg_price", precision = 28, scale = 12)
    private BigDecimal avgPrice;

    @Column(name = "reduce_only", nullable = false, updatable = false)
    private boolean reduceOnly;

    @Column(name = "close_position", nullable = false, updatable = false)
    private boolean closePosition;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_status", nullable = false, length = 32)
    private OrderStatus orderStatus;

    /** Why an order ended other than by filling, e.g. {@code IOC_NOT_FILLABLE}; otherwise {@code null}. */
    @Column(name = "status_reason", length = 64)
    private String statusReason;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** For JPA only. */
    protected PaperOrder() {}

    private PaperOrder(
            UUID accountId,
            UUID pairId,
            String clientOrderId,
            OrderSide side,
            OrderType orderType,
            TimeInForce timeInForce,
            BigDecimal limitPrice,
            BigDecimal origQuantity,
            BigDecimal quoteOrderAmount) {
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
        this.pairId = Objects.requireNonNull(pairId, "pairId must not be null");
        this.marketType = MarketType.SPOT;
        this.clientOrderId = Objects.requireNonNull(clientOrderId, "clientOrderId must not be null");
        this.side = Objects.requireNonNull(side, "side must not be null");
        this.orderType = Objects.requireNonNull(orderType, "orderType must not be null");
        this.timeInForce = timeInForce;
        this.limitPrice = limitPrice;
        this.origQuantity = origQuantity;
        this.quoteOrderAmount = quoteOrderAmount;
        this.executedQuantity = BigDecimal.ZERO;
        this.cumQuoteAmount = BigDecimal.ZERO;
        this.reduceOnly = false;
        this.closePosition = false;
        this.orderStatus = OrderStatus.NEW;
    }

    /**
     * A Spot MARKET order of a quantity.
     *
     * @param quantity positive, already floored to the pair's step size
     */
    public static PaperOrder spotMarket(
            UUID accountId, UUID pairId, String clientOrderId, OrderSide side, BigDecimal quantity) {
        return new PaperOrder(
                accountId, pairId, clientOrderId, side, OrderType.MARKET, null, null, positive(quantity), null);
    }

    /**
     * A Spot MARKET buy sized by what it spends ("by total"); the quantity follows from the price it executes at.
     *
     * @param quoteAmount positive, in the quote asset
     */
    public static PaperOrder spotMarketBuyFor(
            UUID accountId, UUID pairId, String clientOrderId, BigDecimal quoteAmount) {
        return new PaperOrder(
                accountId,
                pairId,
                clientOrderId,
                OrderSide.BUY,
                OrderType.MARKET,
                null,
                null,
                null,
                positive(quoteAmount));
    }

    /**
     * A Spot LIMIT order.
     *
     * @param price positive, already rounded to the pair's tick size
     * @param quantity positive, already floored to the pair's step size
     */
    public static PaperOrder spotLimit(
            UUID accountId,
            UUID pairId,
            String clientOrderId,
            OrderSide side,
            TimeInForce timeInForce,
            BigDecimal price,
            BigDecimal quantity) {
        return new PaperOrder(
                accountId,
                pairId,
                clientOrderId,
                side,
                OrderType.LIMIT,
                Objects.requireNonNull(timeInForce, "a LIMIT order has a time in force"),
                positive(price),
                positive(quantity),
                null);
    }

    /**
     * What a working Spot LIMIT order holds back: the quote it may spend for a buy, the base it sells for a sell. A
     * buy holds its price times its quantity rounded up to the amount scale, the same figure {@link #buyCost} charges
     * at that price, so a fill at the limit price spends exactly what was locked.
     */
    public BigDecimal lockedAmount() {
        if (orderType != OrderType.LIMIT) {
            throw new IllegalStateException("order " + getId() + " is " + orderType + " and holds nothing back");
        }
        return side == OrderSide.BUY ? buyCost(limitPrice, origQuantity) : origQuantity;
    }

    /** Whether the order still works. */
    public boolean isOpen() {
        return orderStatus.isOpen();
    }

    /**
     * Records the execution of the whole order: the quantity at the price, for {@code quoteAmount}. The order becomes
     * FILLED at {@code at}.
     */
    public void fill(BigDecimal quantity, BigDecimal price, BigDecimal quoteAmount, Instant at) {
        requireOpen("filled");
        if (origQuantity != null && quantity.compareTo(origQuantity) != 0) {
            throw new IllegalArgumentException("order " + getId() + " of " + origQuantity.toPlainString()
                    + " executes whole, not " + quantity.toPlainString());
        }
        executedQuantity = positive(quantity);
        cumQuoteAmount = positive(quoteAmount);
        avgPrice = positive(price).setScale(PRICE_SCALE, RoundingMode.HALF_EVEN);
        end(OrderStatus.FILLED, null, at);
    }

    /** Ends a working order the Trader cancelled. */
    public void cancel(Instant at) {
        requireOpen("cancelled");
        end(OrderStatus.CANCELED, null, at);
    }

    /**
     * Ends an order its time in force does not let wait, e.g. an IOC or FOK limit the price has not reached.
     *
     * @param reason a short code the order history shows
     */
    public void expire(String reason, Instant at) {
        requireOpen("expired");
        end(OrderStatus.EXPIRED, Objects.requireNonNull(reason, "reason"), at);
    }

    /**
     * What a buy of {@code quantity} at {@code price} costs: rounded up to the amount scale, so the wallet never pays
     * less than the trade is worth.
     */
    public static BigDecimal buyCost(BigDecimal price, BigDecimal quantity) {
        return price.multiply(quantity).setScale(Rounding.AMOUNT_SCALE, RoundingMode.UP);
    }

    /**
     * What a sale of {@code quantity} at {@code price} brings: rounded down to the amount scale, so the wallet never
     * receives more than the trade is worth.
     */
    public static BigDecimal saleProceeds(BigDecimal price, BigDecimal quantity) {
        return price.multiply(quantity).setScale(Rounding.AMOUNT_SCALE, RoundingMode.DOWN);
    }

    private void end(OrderStatus status, String reason, Instant at) {
        orderStatus = status;
        statusReason = reason;
        closedAt = Objects.requireNonNull(at, "at must not be null");
    }

    // The service checks the status under the Trader's lock before it asks, so a finished order here is a defect.
    private void requireOpen(String action) {
        if (!isOpen()) {
            throw new IllegalStateException("order " + getId() + " is " + orderStatus + " and cannot be " + action);
        }
    }

    private static BigDecimal positive(BigDecimal value) {
        Objects.requireNonNull(value, "value must not be null");
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("must be positive, was " + value.toPlainString());
        }
        return value;
    }
}
