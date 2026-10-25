package com.cryptopilot.journal.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.journal.model.enums.TradeSource;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.OpenTrade;
import com.cryptopilot.trading.PositionLevelChange;
import com.cryptopilot.trading.PositionLevels;
import com.cryptopilot.trading.RecordedPosition;
import com.cryptopilot.trading.SimulatedTradeOpening;
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
 * {@code TradingJournalRepository}, so this entity only reads the exit columns and the change counters. The learning data of D-111 is mapped by T-048c.
 *
 * <p>Rule: BR-34, BR-35, BR-36, BR-37, BR-40; D-78, D-97 and its note of 2026-10-24, D-119.
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

    /** The settlement instants known to be missing, oldest first; {@code null} when none. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "missing_funding_times", insertable = false, updatable = false)
    private Instant[] missingFundingTimes;

    /** For JPA only. */
    protected TradingJournal() {}

    /**
     * The OPEN record of a plan that has just filled (BR-34): the fill's price, time and liquidity role, the plan
     * snapshot's quantity and the entry fee, as the plan's module computed them.
     */
    public static TradingJournal openSimulated(SimulatedTradeOpening opening) {
        TradingJournal record = new TradingJournal();
        record.userId = opening.userId();
        record.pairId = opening.pairId();
        record.planId = opening.planId();
        record.source = TradeSource.SIMULATED;
        record.market = opening.market();
        record.direction = opening.direction();
        record.leverage = opening.leverage();
        record.entryPrice = opening.entryPrice();
        record.stopLoss = opening.stopLoss();
        record.takeProfit = opening.takeProfit();
        record.originalStopLoss = opening.stopLoss();
        record.originalTakeProfit = opening.takeProfit();
        record.quantity = opening.quantity();
        // To the microsecond, like the column, so the record reads back as it was written.
        record.entryTime = opening.entryTime().truncatedTo(ChronoUnit.MICROS);
        record.entryLiquidity = opening.entryLiquidity();
        record.entryFee = opening.entryFee();
        record.liquidationPrice = opening.liquidationPrice();
        record.status = PositionStatus.OPEN;
        return record;
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
                fundingIncomplete);
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
                quantity,
                entryLiquidity,
                entryFee,
                stopLoss,
                takeProfit,
                liquidationPrice,
                getVersion());
    }
}
