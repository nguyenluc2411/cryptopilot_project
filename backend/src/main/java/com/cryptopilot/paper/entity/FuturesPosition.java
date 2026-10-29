package com.cryptopilot.paper.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.paper.model.enums.MarginType;
import com.cryptopilot.paper.model.enums.PositionCloseReason;
import com.cryptopilot.paper.model.enums.PositionDirection;
import com.cryptopilot.paper.model.enums.PositionSide;
import com.cryptopilot.paper.model.enums.PositionStatus;
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
 * One life of a paper Futures position, from the fill that opened it until its size is back to zero. A one-way
 * reversal closes the row and opens the next one. An isolated position carries its own wallet; a cross one shares the
 * cross wallet and has none.
 *
 * <p>Rule: TR-14, TR-16; D-127.
 */
@Getter
@Entity
@Table(name = "futures_position")
@AttributeOverride(name = "id", column = @Column(name = "position_id", nullable = false, updatable = false))
public class FuturesPosition extends BaseEntity {

    /** Decimals of a price column, {@code numeric(28,12)}. */
    private static final int ENTRY_SCALE = 12;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "pair_id", nullable = false, updatable = false)
    private UUID pairId;

    @Enumerated(EnumType.STRING)
    @Column(name = "position_side", nullable = false, length = 32, updatable = false)
    private PositionSide positionSide;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 32, updatable = false)
    private PositionDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "margin_type", nullable = false, length = 32, updatable = false)
    private MarginType marginType;

    @Column(name = "leverage", nullable = false)
    private int leverage;

    /** The size, never signed: the direction says which way. */
    @Column(name = "quantity", nullable = false, precision = 28, scale = 12)
    private BigDecimal quantity;

    /** The largest size the position reached. */
    @Column(name = "max_quantity", nullable = false, precision = 28, scale = 12)
    private BigDecimal maxQuantity;

    @Column(name = "entry_price", nullable = false, precision = 28, scale = 12)
    private BigDecimal entryPrice;

    /** The average price of what was closed so far, or {@code null} when nothing was. */
    @Column(name = "close_avg_price", precision = 28, scale = 12)
    private BigDecimal closeAvgPrice;

    @Column(name = "closed_quantity", nullable = false, precision = 28, scale = 12)
    private BigDecimal closedQuantity;

    /** The isolated wallet, {@code null} for a cross position. */
    @Column(name = "isolated_wallet_amount", precision = 28, scale = 8)
    private BigDecimal isolatedWalletAmount;

    @Column(name = "realized_pnl_amount", nullable = false, precision = 28, scale = 8)
    private BigDecimal realizedPnlAmount;

    @Column(name = "commission_amount", nullable = false, precision = 28, scale = 8)
    private BigDecimal commissionAmount;

    @Column(name = "funding_amount", nullable = false, precision = 28, scale = 8)
    private BigDecimal fundingAmount;

    @Column(name = "liquidation_fee_amount", nullable = false, precision = 28, scale = 8)
    private BigDecimal liquidationFeeAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "position_status", nullable = false, length = 32)
    private PositionStatus positionStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "close_reason", length = 32)
    private PositionCloseReason closeReason;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** For JPA only. */
    protected FuturesPosition() {}

    private FuturesPosition(
            UUID userId,
            UUID pairId,
            PositionSide positionSide,
            PositionDirection direction,
            MarginType marginType,
            int leverage,
            BigDecimal quantity,
            BigDecimal entryPrice,
            Instant openedAt) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.pairId = Objects.requireNonNull(pairId, "pairId must not be null");
        this.positionSide = Objects.requireNonNull(positionSide, "positionSide must not be null");
        this.direction = Objects.requireNonNull(direction, "direction must not be null");
        this.marginType = Objects.requireNonNull(marginType, "marginType must not be null");
        this.leverage = leverage;
        this.quantity = positive(quantity, "quantity");
        this.maxQuantity = quantity;
        this.entryPrice = positive(entryPrice, "entryPrice");
        this.closedQuantity = BigDecimal.ZERO;
        this.isolatedWalletAmount = marginType == MarginType.ISOLATED ? BigDecimal.ZERO : null;
        this.realizedPnlAmount = BigDecimal.ZERO;
        this.commissionAmount = BigDecimal.ZERO;
        this.fundingAmount = BigDecimal.ZERO;
        this.liquidationFeeAmount = BigDecimal.ZERO;
        this.positionStatus = PositionStatus.OPEN;
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt must not be null");
    }

    /**
     * A position opened by a fill. An isolated one starts with an empty wallet; the fill's margin is moved in with
     * {@link #adjustIsolatedWallet(BigDecimal)} and recorded as an adjustment.
     */
    public static FuturesPosition open(
            UUID userId,
            UUID pairId,
            PositionSide positionSide,
            PositionDirection direction,
            MarginType marginType,
            int leverage,
            BigDecimal quantity,
            BigDecimal entryPrice,
            Instant openedAt) {
        return new FuturesPosition(
                userId, pairId, positionSide, direction, marginType, leverage, quantity, entryPrice, openedAt);
    }

    /** Whether the position still has a size. */
    public boolean isOpen() {
        return positionStatus == PositionStatus.OPEN;
    }

    /** The size signed by the direction: positive for long, negative for short. */
    public BigDecimal signedQuantity() {
        return direction == PositionDirection.LONG ? quantity : quantity.negate();
    }

    /**
     * Changes the isolated wallet by a signed amount; the result is never negative.
     *
     * @throws IllegalStateException for a cross position, or when the wallet would go below zero
     */
    public void adjustIsolatedWallet(BigDecimal change) {
        if (marginType != MarginType.ISOLATED) {
            throw new IllegalStateException("a cross position has no isolated wallet");
        }
        BigDecimal after = isolatedWalletAmount.add(change);
        if (after.signum() < 0) {
            throw new IllegalStateException("the isolated wallet would go below zero: " + after.toPlainString());
        }
        isolatedWalletAmount = after;
    }

    /**
     * Adds a fill that increases the position.
     *
     * @param quantity the filled quantity, positive
     * @param entryPriceAfter the size-weighted entry price with the fill
     */
    public void increase(BigDecimal quantity, BigDecimal entryPriceAfter) {
        requireOpen();
        this.quantity = this.quantity.add(positive(quantity, "quantity"));
        this.maxQuantity = this.maxQuantity.max(this.quantity);
        this.entryPrice = positive(entryPriceAfter, "entryPriceAfter");
    }

    /**
     * Takes a fill that reduces the position, at its price, with the profit or loss it realised; the entry price
     * stays. Nothing left, the position is closed at {@code at} for {@code reason}; an isolated wallet must have been
     * released by then.
     *
     * @param quantity the closed quantity, positive and at most the size
     */
    public void reduce(
            BigDecimal quantity, BigDecimal price, BigDecimal realizedPnl, PositionCloseReason reason, Instant at) {
        requireOpen();
        positive(quantity, "quantity");
        if (quantity.compareTo(this.quantity) > 0) {
            throw new IllegalArgumentException(
                    "closed " + quantity.toPlainString() + " is more than the size " + this.quantity.toPlainString());
        }
        BigDecimal closedBefore = closedQuantity;
        closedQuantity = closedQuantity.add(quantity);
        BigDecimal closedValue = closeAvgPrice == null ? BigDecimal.ZERO : closeAvgPrice.multiply(closedBefore);
        closeAvgPrice = closedValue
                .add(positive(price, "price").multiply(quantity))
                .divide(closedQuantity, ENTRY_SCALE, RoundingMode.HALF_EVEN);
        realizedPnlAmount = realizedPnlAmount.add(realizedPnl);
        this.quantity = this.quantity.subtract(quantity);
        if (this.quantity.signum() == 0) {
            if (isolatedWalletAmount != null && isolatedWalletAmount.signum() != 0) {
                throw new IllegalStateException("the isolated wallet of a closed position must be released first");
            }
            positionStatus = PositionStatus.CLOSED;
            closeReason = Objects.requireNonNull(reason, "reason must not be null");
            closedAt = Objects.requireNonNull(at, "at must not be null");
        }
    }

    /** Adds a funding fee it paid (negative) or received (positive) (TR-24). */
    public void addFunding(BigDecimal fee) {
        fundingAmount = fundingAmount.add(fee);
    }

    /** Adds the clearance fee its liquidation took (TR-22). */
    public void addLiquidationFee(BigDecimal fee) {
        if (fee.signum() < 0) {
            throw new IllegalArgumentException("a liquidation fee is not negative, was " + fee.toPlainString());
        }
        liquidationFeeAmount = liquidationFeeAmount.add(fee);
    }

    /** Sets the leverage of the open position; the caller checked it against the brackets and the margin. */
    public void changeLeverage(int leverage) {
        if (leverage < 1) {
            throw new IllegalArgumentException("leverage must be at least 1, was " + leverage);
        }
        this.leverage = leverage;
    }

    private void requireOpen() {
        if (!isOpen()) {
            throw new IllegalStateException("position " + getId() + " is closed");
        }
    }

    private static BigDecimal positive(BigDecimal value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive, was " + value.toPlainString());
        }
        return value;
    }
}
