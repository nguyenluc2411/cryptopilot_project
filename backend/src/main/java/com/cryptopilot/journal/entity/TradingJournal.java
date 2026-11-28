package com.cryptopilot.journal.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.journal.model.FillIndicators;
import com.cryptopilot.journal.model.enums.TradeSource;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.OpenTrade;
import com.cryptopilot.trading.PositionLevelChange;
import com.cryptopilot.trading.PositionLevels;
import com.cryptopilot.trading.RecordedPosition;
import com.cryptopilot.trading.SimulatedFillLearning;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import com.cryptopilot.trading.model.enums.PositionStatus;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A journal record: one trade, simulated from a filled plan or entered by the Trader. Only the {@code journal} module
 * writes the table (TECHNICAL_DESIGN section 2).
 *
 * <p>The stop loss and take profit of an open position live here and nowhere else, versioned by the row's
 * {@code version}: the levels the exits read are these columns (D-119). The levels the plan was made with stay on the
 * plan, which never changes once executed.
 *
 * <p>The close, a later funding correction and a change of the levels (TP-E) are compare-and-set updates in
 * {@code TradingJournalRepository}, so this entity only reads the exit columns and the change counters. The learning
 * data of a fill (D-111 M4) is written with the OPEN record and never changed, except the two extremes, which only the
 * repository's monotonic update widens.
 *
 * <p>Rule: BR-34, BR-35, BR-36, BR-37, BR-40, BR-69, BR-70; D-78, D-97 and its note of 2026-10-24, D-111, D-112, D-119.
 *
 * <p>Reference: Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley, ch. 5 (an entity is defined by its
 * identity and lifecycle).
 */
@Getter
@Entity
@Table(name = "trading_journal")
@AttributeOverride(name = "id", column = @Column(name = "journal_id", nullable = false, updatable = false))
public class TradingJournal extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "pair_id", nullable = false, updatable = false)
    private UUID pairId;

    /** The plan of a SIMULATED trade; {@code null} for a MANUAL one. */
    @Column(name = "plan_id", updatable = false)
    private UUID planId;

    /** The strategy the Trader assigned; {@code null} until then. Written by the review (T-049). */
    @Column(name = "strategy_id", insertable = false, updatable = false)
    private UUID strategyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 32, updatable = false)
    private TradeSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "market_type", nullable = false, length = 32)
    private MarketType market;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 32)
    private Direction direction;

    /** The leverage; {@code null} on Spot. */
    @Column(name = "leverage")
    private Integer leverage;

    /** The actual fill price of the entry. */
    @Column(name = "entry_price", nullable = false, precision = 28, scale = 12)
    private BigDecimal entryPrice;

    /** The current stop loss of the position (D-119). */
    @Column(name = "stop_loss_price", precision = 28, scale = 12)
    private BigDecimal stopLoss;

    /** The current take profit of the position (D-119). */
    @Column(name = "take_profit_price", precision = 28, scale = 12)
    private BigDecimal takeProfit;

    @Column(name = "quantity", nullable = false, precision = 28, scale = 12)
    private BigDecimal quantity;

    /** When the entry filled (D-78). */
    @Column(name = "entry_time", nullable = false)
    private Instant entryTime;

    /** MAKER when the entry rested and was then reached, TAKER when it executed against the market (BR-36). */
    @Enumerated(EnumType.STRING)
    @Column(name = "entry_liquidity", length = 32)
    private LiquidityRole entryLiquidity;

    /** The fee of the entry fill alone; {@code fee_amount} holds entry + exit once closed (D-97, D-119). */
    @Column(name = "entry_fee_amount", precision = 28, scale = 8)
    private BigDecimal entryFee;

    /** The liquidation price of a Futures position at its fill price; {@code null} for Spot (V36). */
    @Column(name = "liquidation_price", precision = 28, scale = 12, updatable = false)
    private BigDecimal liquidationPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "trade_status", nullable = false, length = 32)
    private PositionStatus status;

    @Column(name = "exit_price", precision = 28, scale = 12, insertable = false, updatable = false)
    private BigDecimal exitPrice;

    @Column(name = "exit_time", insertable = false, updatable = false)
    private Instant exitTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "close_reason", length = 32, insertable = false, updatable = false)
    private CloseReason closeReason;

    /** The price result only (BR-35). */
    @Column(name = "realized_pnl", precision = 28, scale = 8, insertable = false, updatable = false)
    private BigDecimal realizedPnl;

    /** The entry fee plus the exit fee, written at the close (BR-36). */
    @Column(name = "fee_amount", precision = 28, scale = 8, insertable = false, updatable = false)
    private BigDecimal feeAmount;

    /** The signed funding known so far; {@code null} for Spot (BR-37). */
    @Column(name = "funding_fee_amount", precision = 28, scale = 8, insertable = false, updatable = false)
    private BigDecimal fundingFeeAmount;

    /** Whether a funding settlement may still be missing, so the net is provisional (D-97 note of 2026-10-24). */
    @Column(name = "funding_incomplete", nullable = false, insertable = false, updatable = false)
    private boolean fundingIncomplete;

    /** The stop loss at the fill; never changed, whatever the Trader does to the current one (V37, D-124). */
    @Column(name = "original_stop_loss_price", precision = 28, scale = 12, updatable = false)
    private BigDecimal originalStopLoss;

    /** The take profit at the fill; never changed (V37, D-124). */
    @Column(name = "original_take_profit_price", precision = 28, scale = 12, updatable = false)
    private BigDecimal originalTakeProfit;

    /** Whether a stop loss was ever moved further from the entry than the original; written by the change only. */
    @Column(name = "stop_widened", nullable = false, updatable = false)
    private boolean stopWidened;

    /** How many changes of the levels were made; written by the change only. */
    @Column(name = "level_change_count", nullable = false, updatable = false)
    private int levelChangeCount;

    /** The Trader's review (SCR-21); written by T-049. */
    @Column(name = "setup_description", insertable = false, updatable = false)
    private String setupDescription;

    /** The Trader's review (SCR-21); written by T-049. */
    @Column(name = "lessons_learned", insertable = false, updatable = false)
    private String lessonsLearned;

    /** The Trader's review (SCR-21); written by T-049. */
    @Column(name = "mistakes", insertable = false, updatable = false)
    private String mistakes;

    /** The Trader's review (SCR-21); written by T-049. */
    @Column(name = "chart_image_url", length = 500, insertable = false, updatable = false)
    private String chartImageUrl;

    /** The media storage key of the chart image, so a replaced or removed one is deleted (V68); written by T-049. */
    @Column(name = "chart_image_public_id", insertable = false, updatable = false)
    private String chartImagePublicId;

    /** The settlement instants known to be missing, oldest first; {@code null} when none. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "missing_funding_times", insertable = false, updatable = false)
    private Instant[] missingFundingTimes;

    /** The entered price for LIMIT and STOP, the reference price of the last draft save for MARKET (BR-69). */
    @Column(name = "planned_entry_price", precision = 28, scale = 12)
    private BigDecimal plannedEntryPrice;

    /** The deviation of the fill from the planned entry in R; positive is a worse entry (BR-69). */
    @Column(name = "entry_deviation_r", precision = 20, scale = 8)
    private BigDecimal entryDeviationR;

    /** The loss at the stop loss the plan was sized on: the denominator of every R (BR-70). */
    @Column(name = "risk_amount", precision = 28, scale = 8)
    private BigDecimal riskAmount;

    /** The plan's chart timeframe, the one the fill's indicators are read on (D-112). */
    @Column(name = "analysis_timeframe", length = 32)
    private String analysisTimeframe;

    /** The preset name and version both setup scores use, e.g. {@code DAY_TRADING v1} (D-112). */
    @Column(name = "setup_score_preset", length = 32)
    private String setupScorePreset;

    @Column(name = "setup_score_at_plan", precision = 5, scale = 2)
    private BigDecimal setupScoreAtPlan;

    @Column(name = "setup_score_at_fill", precision = 5, scale = 2)
    private BigDecimal setupScoreAtFill;

    /** Whether the score fell by at least {@code SETUP_SCORE_DROP_WARN}; {@code null} when nothing was compared. */
    @Column(name = "setup_score_drop_warned")
    private Boolean setupScoreDropWarned;

    /** The indicators at the fill as JSON, see {@link FillIndicators}; {@code null} when none were read. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fill_indicators")
    private String fillIndicators;

    /** The most adverse price while open; widened by a monotonic update in the repository only (BR-70). */
    @Column(name = "max_adverse_price", precision = 28, scale = 12, updatable = false)
    private BigDecimal maxAdversePrice;

    /** The most favourable price while open; widened by a monotonic update in the repository only (BR-70). */
    @Column(name = "max_favorable_price", precision = 28, scale = 12, updatable = false)
    private BigDecimal maxFavorablePrice;

    /** The Spot buy order a record from paper trading was opened by (RF-35); {@code null} otherwise. */
    @Column(name = "paper_order_id", updatable = false)
    private UUID paperOrderId;

    /** The paper Futures position a record follows (RF-35); {@code null} otherwise. */
    @Column(name = "paper_position_id", updatable = false)
    private UUID paperPositionId;

    /** What a sale has closed of an OPEN Spot record so far; written by the repository only (RF-35). */
    @Column(name = "exit_quantity", insertable = false, updatable = false)
    private BigDecimal exitQuantity;

    /** For JPA only. */
    protected TradingJournal() {}

    /**
     * The OPEN record of a paper fill (RF-35): a plan's order (SIMULATED, its plan's levels) or a demo order
     * (DEMO_ORDER, no levels: the dashboard flags it as without a stop loss).
     *
     * @param planId the plan the order carries out, or {@code null} for a demo order
     * @param paperOrderId the Spot buy order, or {@code null} for a Futures position
     * @param paperPositionId the Futures position, or {@code null} for a Spot buy
     */
    public static TradingJournal openFromPaper(
            UUID userId,
            UUID pairId,
            UUID planId,
            MarketType market,
            Direction direction,
            Integer leverage,
            BigDecimal entryPrice,
            BigDecimal quantity,
            Instant entryTime,
            LiquidityRole entryLiquidity,
            BigDecimal entryFee,
            BigDecimal stopLoss,
            BigDecimal takeProfit,
            UUID paperOrderId,
            UUID paperPositionId) {
        TradingJournal record = new TradingJournal();
        record.userId = userId;
        record.pairId = pairId;
        record.planId = planId;
        record.source = planId == null ? TradeSource.DEMO_ORDER : TradeSource.SIMULATED;
        record.market = market;
        record.direction = direction;
        record.leverage = leverage;
        record.entryPrice = entryPrice;
        record.quantity = quantity;
        record.entryTime = entryTime.truncatedTo(ChronoUnit.MICROS);
        record.entryLiquidity = entryLiquidity;
        record.entryFee = entryFee;
        record.stopLoss = stopLoss;
        record.takeProfit = takeProfit;
        record.originalStopLoss = stopLoss;
        record.originalTakeProfit = takeProfit;
        record.status = PositionStatus.OPEN;
        record.paperOrderId = paperOrderId;
        record.paperPositionId = paperPositionId;
        return record;
    }

    /**
     * The learning data of the plan's entry fill (BR-70), added to the record its paper fill opened (RF-33).
     *
     * <p>Rule: BR-69, BR-70; D-111 (M2, M3, M4), D-112.
     */
    public void recordFillLearning(SimulatedFillLearning learning) {
        plannedEntryPrice = learning.plannedEntryPrice();
        entryDeviationR = learning.entryDeviationR();
        riskAmount = learning.riskAmount();
        analysisTimeframe = learning.analysisTimeframe();
        setupScorePreset = learning.setupScorePreset();
        setupScoreAtPlan = learning.setupScoreAtPlan();
        setupScoreAtFill = learning.setupScoreAtFill();
        setupScoreDropWarned = learning.setupScoreDropWarned();
        fillIndicators = learning.indicators() == null
                ? null
                : FillIndicators.of(learning.indicators()).toJson();
    }

    /** The indicators stored at the fill, or {@code null} when none were read. */
    public FillIndicators indicatorsAtFill() {
        return FillIndicators.fromJson(fillIndicators);
    }

    /** The position as the plan detail shows it. Only a SIMULATED record has a plan, and only it is asked. */
    public RecordedPosition toPosition() {
        return new RecordedPosition(
                planId,
                status,
                entryPrice,
                quantity,
                entryTime,
                entryFee,
                liquidationPrice,
                exitPrice,
                exitTime,
                closeReason,
                realizedPnl,
                feeAmount,
                fundingFeeAmount,
                fundingIncomplete,
                paperOrderId != null || paperPositionId != null);
    }

    /** The current and original levels of the record, with the history of their changes, oldest first (D-124). */
    public PositionLevels toLevels(List<PositionLevelChange> changes) {
        return new PositionLevels(
                stopLoss, takeProfit, originalStopLoss, originalTakeProfit, stopWidened, levelChangeCount, changes);
    }

    /** The OPEN position as the matching engine tracks it and its close is computed from. */
    public OpenTrade toOpenTrade() {
        return new OpenTrade(
                getId(),
                planId,
                userId,
                pairId,
                market,
                direction,
                entryPrice,
                entryTime,
                openQuantity(),
                entryLiquidity,
                entryFee,
                stopLoss,
                takeProfit,
                liquidationPrice,
                getVersion());
    }

    /** What is still open: the quantity less what a Spot sale or a Futures reduce has closed so far (RF-33). */
    private BigDecimal openQuantity() {
        return exitQuantity == null ? quantity : quantity.subtract(exitQuantity);
    }
}
