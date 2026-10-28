package com.cryptopilot.paper.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.paper.model.enums.MarginAdjustmentRefType;
import com.cryptopilot.paper.model.enums.MarginAdjustmentType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

/**
 * One change of an isolated wallet, never updated: the isolated wallet of a position is always the sum of its rows.
 * Moving margin between the cross wallet and a position writes no ledger entry, so this is where every such move is
 * audited.
 *
 * <p>Rule: TR-16, TR-17, TR-22, TR-24; D-127.
 */
@Getter
@Entity
@Table(name = "futures_margin_adjustment")
@AttributeOverride(name = "id", column = @Column(name = "adjustment_id", nullable = false, updatable = false))
public class FuturesMarginAdjustment extends BaseEntity {

    @Column(name = "position_id", nullable = false, updatable = false)
    private UUID positionId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "pair_id", nullable = false, updatable = false)
    private UUID pairId;

    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_type", nullable = false, length = 32, updatable = false)
    private MarginAdjustmentType adjustmentType;

    /** The signed change, never zero. */
    @Column(name = "amount", nullable = false, precision = 28, scale = 8, updatable = false)
    private BigDecimal amount;

    /** The isolated wallet after the change. */
    @Column(name = "iw_after_amount", nullable = false, precision = 28, scale = 8, updatable = false)
    private BigDecimal iwAfterAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "ref_type", length = 32, updatable = false)
    private MarginAdjustmentRefType refType;

    @Column(name = "ref_id", updatable = false)
    private UUID refId;

    /** For JPA only. */
    protected FuturesMarginAdjustment() {}

    private FuturesMarginAdjustment(
            FuturesPosition position,
            MarginAdjustmentType adjustmentType,
            BigDecimal amount,
            MarginAdjustmentRefType refType,
            UUID refId) {
        this.positionId = position.getId();
        this.userId = position.getUserId();
        this.pairId = position.getPairId();
        this.adjustmentType = Objects.requireNonNull(adjustmentType, "adjustmentType must not be null");
        if (amount.signum() == 0) {
            throw new IllegalArgumentException("an adjustment changes the wallet, its amount is not zero");
        }
        if ((refType == null) != (refId == null)) {
            throw new IllegalArgumentException("a reference names its type and its id, or neither");
        }
        this.amount = amount;
        this.iwAfterAmount = position.getIsolatedWalletAmount();
        this.refType = refType;
        this.refId = refId;
    }

    /**
     * Records a change already applied to the position's isolated wallet, with the wallet after it.
     *
     * @param refType what caused it, or {@code null}
     * @param refId the id of what caused it, or {@code null}
     */
    public static FuturesMarginAdjustment of(
            FuturesPosition position,
            MarginAdjustmentType adjustmentType,
            BigDecimal amount,
            MarginAdjustmentRefType refType,
            UUID refId) {
        return new FuturesMarginAdjustment(position, adjustmentType, amount, refType, refId);
    }
}
