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
 * <p>This stage of TR-02 places Spot MARKET and LIMIT orders. An order executes in one or more fills: one per level
 * of the exchange's book it takes on arrival (TR-06, Q-T7), and a waiting LIMIT order the rest of its quantity at
 * once when the market reaches it. Part executed, it is {@code PARTIALLY_FILLED} and still works. The columns of the
 * later stages (stop and trailing prices, the position side, order lists) are left unmapped until those stages use
 * them; the database fills them with null.
 *
 * <p>What a working order holds back is not stored: it follows from the order and what of it is left, so
 * {@link #lockedAmount()} gives the same figure at a fill, at a cancel and after a restart.
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

    /** Why a filled order is flagged: it took more than the depth shown, the rest at the last level's price (Q-T7). */
    public static final String DEPTH_EXHAUSTED = "DEPTH_EXHAUSTED";

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

    /**
     * Why an order ended other than by filling, e.g. {@code NOT_FILLABLE_ON_ARRIVAL}, or {@link #DEPTH_EXHAUSTED} on
     * one that filled past the book shown; otherwise {@code null}.
     */
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
     * A Spot LIMIT_MAKER order: a limit with no time in force that only ever waits (TR-12).
     *
     * @param price positive, already rounded to the pair's tick size
     * @param quantity positive, already floored to the pair's step size
     */
    public static PaperOrder spotLimitMaker(
            UUID accountId, UUID pairId, String clientOrderId, OrderSide side, BigDecimal price, BigDecimal quantity) {
        return new PaperOrder(
                accountId,
                pairId,
                clientOrderId,
                side,
                OrderType.LIMIT_MAKER,
                null,
                positive(price),
                positive(quantity),
                null);
    }

    /** Whether the order waits at its own price: a LIMIT or a LIMIT_MAKER order. */
    public boolean waitsAtPrice() {
        return orderType == OrderType.LIMIT || orderType == OrderType.LIMIT_MAKER;
    }

    /**
     * What a working Spot LIMIT order holds back for the quantity it has not executed yet: the quote it may spend for a
     * buy, the base it sells for a sell. A buy holds its price times that quantity rounded up to the amount scale, the
     * same figure {@link #buyCost} charges at that price, so a fill of the rest at the limit price spends exactly what
     * is locked.
     */
    public BigDecimal lockedAmount() {
        return lockedFor(remainingQuantity());
    }

    /** What the order holds back once {@code quantity} more of it has executed; zero once all of it has. */
    public BigDecimal lockedAfter(BigDecimal quantity) {
        return lockedFor(remainingQuantity().subtract(quantity));
    }

    /** The quantity not executed yet. */
    public BigDecimal remainingQuantity() {
        if (origQuantity == null) {
            throw new IllegalStateException("order " + getId() + " is sized by what it spends, not by a quantity");
        }
        return origQuantity.subtract(executedQuantity);
    }

    private BigDecimal lockedFor(BigDecimal quantity) {
        if (!waitsAtPrice()) {
            throw new IllegalStateException("order " + getId() + " is " + orderType + " and holds nothing back");
        }
        if (quantity.signum() < 0) {
            throw new IllegalArgumentException("order " + getId() + " has less than " + quantity.negate() + " left");
        }
        return side == OrderSide.BUY ? buyCost(limitPrice, quantity) : quantity;
    }

    /** Whether the order still works. */
    public boolean isOpen() {
        return orderStatus.isOpen();
    }

    /**
     * Records one execution: {@code quantity} at {@code price}, for {@code quoteAmount}. The average price is the
     * quantity-weighted mean of the executions' prices. An order of a quantity becomes FILLED at {@code at} once all
     * of it has executed, PARTIALLY_FILLED before; one sized by what it spends stays PARTIALLY_FILLED until
     * {@link #completeFill}.
     */
    public void fill(BigDecimal quantity, BigDecimal price, BigDecimal quoteAmount, Instant at) {
        requireOpen("filled");
        positive(quantity);
        positive(price);
        BigDecimal executed = executedQuantity.add(quantity);
        if (origQuantity != null && executed.compareTo(origQuantity) > 0) {
            throw new IllegalArgumentException("order " + getId() + " of " + origQuantity.toPlainString() + " has "
                    + remainingQuantity().toPlainString() + " left, not " + quantity.toPlainString());
        }
        BigDecimal value = avgPrice == null ? BigDecimal.ZERO : avgPrice.multiply(executedQuantity);
        avgPrice = value.add(price.multiply(quantity)).divide(executed, PRICE_SCALE, RoundingMode.HALF_EVEN);
        executedQuantity = executed;
        cumQuoteAmount = cumQuoteAmount.add(positive(quoteAmount));
        if (origQuantity != null && executed.compareTo(origQuantity) == 0) {
            end(OrderStatus.FILLED, null, at);
        } else {
            orderStatus = OrderStatus.PARTIALLY_FILLED;
        }
    }

    /** Ends an order sized by what it spends, once it has spent what it could: FILLED at {@code at}. */
    public void completeFill(Instant at) {
        requireOpen("completed");
        if (executedQuantity.signum() == 0) {
            throw new IllegalStateException("order " + getId() + " has executed nothing to complete");
        }
        end(OrderStatus.FILLED, null, at);
    }

    /** Flags a filled order that took more than the depth shown, the rest at the last level's price (Q-T7). */
    public void flagDepthExhausted() {
        if (orderStatus != OrderStatus.FILLED) {
            throw new IllegalStateException("order " + getId() + " is " + orderStatus + ", not FILLED");
        }
        statusReason = DEPTH_EXHAUSTED;
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
