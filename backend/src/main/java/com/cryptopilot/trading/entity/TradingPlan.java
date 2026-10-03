package com.cryptopilot.trading.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.calculator.PositionSizeCalculator;
import com.cryptopilot.trading.calculator.warning.WarningEvaluator;
import com.cryptopilot.trading.exception.IllegalPlanStateException;
import com.cryptopilot.trading.exception.PlanActivationBlockedException;
import com.cryptopilot.trading.model.PlanCalculation;
import com.cryptopilot.trading.model.PlanDetails;
import com.cryptopilot.trading.model.PlanSnapshot;
import com.cryptopilot.trading.model.PlanWarning;
import com.cryptopilot.trading.model.RiskInput;
import com.cryptopilot.trading.model.RiskInputRejected;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.MarginMode;
import com.cryptopilot.trading.model.enums.PlanStatus;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;

/**
 * A trading plan and the root of its aggregate: the plan's values, the calculation snapshot and the warnings it holds
 * change only through the methods below, which keep the lifecycle of BR-32 and the snapshot rule of BR-31.
 *
 * <ul>
 *   <li>Only a DRAFT is edited; each save replaces its values, its snapshot and its warnings.
 *   <li>Activation re-calculates the stored plan (only a MARKET entry price may move), stores the new snapshot and is
 *       refused while a BLOCKING warning exists, as {@link WarningEvaluator#blocksActivation} decides (MSG18).
 *   <li>After activation the snapshot is fixed.
 *   <li>Every status change goes through the transition table of {@link PlanStatus} and records its instant.
 * </ul>
 *
 * <p>The plan's inputs are checked by the calculation that sized them (BR-21, BR-22, BR-30): the plan asks
 * {@link PositionSizeCalculator} again rather than repeating the comparisons, so the rules live in one place, and a
 * rejection is reported per field ({@link FieldValidationException}).
 *
 * <p>Rule: BR-21, BR-22, BR-31, BR-32; MSG18; TECHNICAL_DESIGN 3.2 and 4.3; D-66, D-67, D-68, D-69.
 *
 * <p>Reference: Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley, ch. 6 "Aggregates".
 * <p>Reference: Vernon, V. (2013). <i>Implementing Domain-Driven Design</i>. Addison-Wesley, ch. 10 "Aggregates".
 * <p>Reference: Harel, D. (1987). Statecharts: A visual formalism for complex systems. <i>Science of Computer
 * Programming</i>, 8(3), 231–274.
 */
@Getter
@Entity
@Table(name = "trading_plan")
@AttributeOverride(name = "id", column = @Column(name = "plan_id", nullable = false, updatable = false))
public class TradingPlan extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "pair_id", nullable = false, updatable = false)
    private UUID pairId;

    @Enumerated(EnumType.STRING)
    @Column(name = "market_type", nullable = false, length = 32)
    private MarketType market;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 32)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 32)
    private EntryType entryType;

    /** The entry price the snapshot was calculated with; for MARKET, the last price at that moment. */
    @Column(name = "entry_price", precision = 28, scale = 12)
    private BigDecimal entryPrice;

    @Column(name = "stop_loss_price", nullable = false, precision = 28, scale = 12)
    private BigDecimal stopLoss;

    @Column(name = "take_profit_price", nullable = false, precision = 28, scale = 12)
    private BigDecimal takeProfit;

    @Column(name = "capital_amount", nullable = false, precision = 28, scale = 8)
    private BigDecimal capital;

    @Column(name = "risk_percent", nullable = false, precision = 6, scale = 3)
    private BigDecimal riskPercent;

    /** The leverage on Futures; {@code null} on Spot (BR-21). */
    @Column(name = "leverage")
    private Integer leverage;

    /** ISOLATED on Futures; {@code null} on Spot (BR-26). */
    @Enumerated(EnumType.STRING)
    @Column(name = "margin_mode", length = 32)
    private MarginMode marginMode;

    @Getter(AccessLevel.NONE)
    @Column(name = "position_quantity", precision = 28, scale = 12)
    private BigDecimal positionQuantity;

    @Getter(AccessLevel.NONE)
    @Column(name = "notional_value", precision = 28, scale = 8)
    private BigDecimal notionalValue;

    @Getter(AccessLevel.NONE)
    @Column(name = "initial_margin", precision = 28, scale = 8)
    private BigDecimal initialMargin;

    @Getter(AccessLevel.NONE)
    @Column(name = "maintenance_margin_rate_used", precision = 12, scale = 8)
    private BigDecimal maintenanceMarginRateUsed;

    @Getter(AccessLevel.NONE)
    @Column(name = "risk_amount", precision = 28, scale = 8)
    private BigDecimal riskAmount;

    @Getter(AccessLevel.NONE)
    @Column(name = "reward_amount", precision = 28, scale = 8)
    private BigDecimal rewardAmount;

    @Getter(AccessLevel.NONE)
    @Column(name = "risk_reward_ratio", precision = 20, scale = 8)
    private BigDecimal riskRewardRatio;

    @Getter(AccessLevel.NONE)
    @Column(name = "estimated_liquidation_price", precision = 28, scale = 12)
    private BigDecimal estimatedLiquidationPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_status", nullable = false, length = 32)
    private PlanStatus status;

    @Column(name = "plan_note")
    private String note;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "executed_at")
    private Instant executedAt;

    /** The price the entry was filled at; set with EXECUTED (BR-33). */
    @Column(name = "fill_price", precision = 28, scale = 12)
    private BigDecimal fillPrice;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "expired_at")
    private Instant expiredAt;

    /**
     * Inside the aggregate: created, replaced and deleted with the plan (D-68). Lazy, so a list of plans costs one
     * statement; ordered by the time-ordered key, which keeps the order the warnings were raised in.
     */
    @Getter(AccessLevel.NONE)
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false, updatable = false)
    @OrderBy("id")
    private List<TradingPlanWarning> warnings = new ArrayList<>();

    /** For JPA only. */
    protected TradingPlan() {}

    private TradingPlan(UUID userId, UUID pairId) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.pairId = Objects.requireNonNull(pairId, "pairId");
        this.status = PlanStatus.DRAFT;
    }

    /**
     * A new DRAFT plan (UC-16), saved with its warnings whatever their severity.
     *
     * @param calculation the plan's inputs with their sizing and, on Futures, liquidation estimate
     * @param details entry type, expiry and note
     * @param warnings the warnings the calculation raises, most severe first
     */
    public static TradingPlan draft(
            UUID userId, UUID pairId, PlanCalculation calculation, PlanDetails details, List<PlanWarning> warnings) {
        TradingPlan plan = new TradingPlan(userId, pairId);
        plan.save(calculation, details, warnings);
        return plan;
    }

    /** Replaces the values, the snapshot and the warnings of a DRAFT plan (UC-17). */
    public void updateDraft(PlanCalculation calculation, PlanDetails details, List<PlanWarning> warnings) {
        if (!isEditable()) {
            throw new IllegalPlanStateException(getId(), status, "edit");
        }
        save(calculation, details, warnings);
    }

    /**
     * Activates a DRAFT plan with the calculation re-run at this moment (UC-18, BR-31). Nothing changes when it is
     * refused.
     *
     * @param calculation the stored plan calculated again; only a MARKET entry price may differ
     * @param warnings the warnings of that calculation
     * @param now the activation instant
     * @throws IllegalPlanStateException when the plan is not a DRAFT
     * @throws FieldValidationException when the recalculated inputs are rejected, e.g. a MARKET last price past the
     *     stop loss (MSG15, MSG16)
     * @throws PlanActivationBlockedException when a warning is BLOCKING (MSG18)
     * @throws BusinessException {@code VALIDATION_FAILED} when the expiry is not after {@code now}
     */
    public void activate(PlanCalculation calculation, List<PlanWarning> warnings, Instant now) {
        Objects.requireNonNull(now, "now");
        requireTransition(PlanStatus.ACTIVE);
        requireAccepted(calculation);
        requireSameTerms(calculation.plan());
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED, "the expiry " + expiresAt + " is not after the activation " + now);
        }
        if (WarningEvaluator.blocksActivation(warnings)) {
            throw new PlanActivationBlockedException(getId());
        }
        entryPrice = calculation.plan().entryPrice();
        replaceSnapshot(PlanSnapshot.of(calculation));
        replaceWarnings(warnings);
        status = PlanStatus.ACTIVE;
        // The column keeps microseconds; the activation guard (D-77) must see the same instant live and after a
        // restart.
        activatedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * The entry was filled (NSF-07, BR-33); the position is the journal's from here.
     *
     * @param now when the fill counts, kept to the microsecond like the column
     * @param price the fill price
     */
    public void markExecuted(Instant now, BigDecimal price) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(price, "price");
        requireTransition(PlanStatus.EXECUTED);
        status = PlanStatus.EXECUTED;
        executedAt = now.truncatedTo(ChronoUnit.MICROS);
        fillPrice = price;
    }

    /** Cancelled by the Trader (UC-19), from DRAFT or ACTIVE. */
    public void cancel(Instant now) {
        Objects.requireNonNull(now, "now");
        requireTransition(PlanStatus.CANCELLED);
        status = PlanStatus.CANCELLED;
        cancelledAt = now;
    }

    /** An ACTIVE plan whose expiry has come, at or before {@code now}, without a fill (NSF-09). */
    public void expire(Instant now) {
        Objects.requireNonNull(now, "now");
        requireTransition(PlanStatus.EXPIRED);
        if (expiresAt == null || expiresAt.isAfter(now)) {
            throw new IllegalPlanStateException(getId(), status, "expire before its expiry " + expiresAt);
        }
        status = PlanStatus.EXPIRED;
        expiredAt = now;
    }

    /** Whether the plan's values may still be changed (BR-32). */
    public boolean isEditable() {
        return status == PlanStatus.DRAFT;
    }

    /** Whether the stored warnings would refuse an activation (MSG18). */
    public boolean hasBlockingWarning() {
        return WarningEvaluator.blocksActivation(getWarnings());
    }

    /** The stored calculation results (BR-31). */
    public PlanSnapshot getSnapshot() {
        return new PlanSnapshot(
                positionQuantity,
                notionalValue,
                initialMargin,
                maintenanceMarginRateUsed,
                riskAmount,
                rewardAmount,
                riskRewardRatio,
                estimatedLiquidationPrice);
    }

    /** The stored warnings, most severe first. */
    public List<PlanWarning> getWarnings() {
        return warnings.stream().map(TradingPlanWarning::toPlanWarning).toList();
    }

    private void save(PlanCalculation calculation, PlanDetails details, List<PlanWarning> newWarnings) {
        Objects.requireNonNull(details, "details");
        requireAccepted(calculation);
        RiskInput in = calculation.plan();
        boolean futures = calculation.isFutures();
        market = in.market();
        direction = in.direction();
        entryType = details.entryType();
        entryPrice = in.entryPrice();
        stopLoss = in.stopLoss();
        takeProfit = in.takeProfit();
        capital = in.capital();
        riskPercent = in.riskPercent();
        leverage = futures ? in.leverage() : null;
        marginMode = futures ? MarginMode.ISOLATED : null;
        expiresAt = details.expiresAt();
        note = details.note();
        replaceSnapshot(PlanSnapshot.of(calculation));
        replaceWarnings(newWarnings);
    }

    /**
     * BR-21, BR-22 and BR-30, as the sizing checks them. A Trader can reach a rejection, e.g. a MARKET plan whose last
     * price has moved past its stop loss by the time it is activated, so it is a validation error naming each field.
     */
    private static void requireAccepted(PlanCalculation calculation) {
        Objects.requireNonNull(calculation, "calculation");
        if (PositionSizeCalculator.calculate(calculation.plan()) instanceof RiskInputRejected rejected) {
            throw new FieldValidationException(
                    "the calculation rejects the plan's inputs: " + rejected.violations(), rejected.fieldErrors());
        }
    }

    /** Activation re-calculates this plan, not another one: only a MARKET entry may move to the last price. */
    private void requireSameTerms(RiskInput in) {
        boolean same = in.market() == market
                && in.direction() == direction
                && in.stopLoss().compareTo(stopLoss) == 0
                && in.takeProfit().compareTo(takeProfit) == 0
                && in.capital().compareTo(capital) == 0
                && in.riskPercent().compareTo(riskPercent) == 0
                && (leverage == null || in.leverage() == leverage)
                && (entryType == EntryType.MARKET || in.entryPrice().compareTo(entryPrice) == 0);
        if (!same) {
            throw new IllegalArgumentException("the activation calculation is not for plan " + getId() + "'s values");
        }
    }

    private void requireTransition(PlanStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalPlanStateException(getId(), status, "move to " + target);
        }
    }

    private void replaceSnapshot(PlanSnapshot snapshot) {
        positionQuantity = snapshot.positionQuantity();
        notionalValue = snapshot.notionalValue();
        initialMargin = snapshot.initialMargin();
        maintenanceMarginRateUsed = snapshot.maintenanceMarginRateUsed();
        riskAmount = snapshot.riskAmount();
        rewardAmount = snapshot.rewardAmount();
        riskRewardRatio = snapshot.riskRewardRatio();
        estimatedLiquidationPrice = snapshot.estimatedLiquidationPrice();
    }

    private void replaceWarnings(List<PlanWarning> newWarnings) {
        List<TradingPlanWarning> replacement =
                newWarnings.stream().map(TradingPlanWarning::new).toList();
        warnings.clear();
        warnings.addAll(replacement);
    }
}
