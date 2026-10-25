package com.cryptopilot.journal.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.journal.model.enums.TradeSource;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.RecordedPosition;
import com.cryptopilot.trading.SimulatedTradeOpening;
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
import java.util.UUID;
import lombok.Getter;

/**
 * A journal record: one trade, simulated from a filled plan or entered by the Trader. Only the {@code journal} module
 * writes the table (TECHNICAL_DESIGN section 2).
 *
 * <p>The stop loss and take profit of an open position live here and nowhere else, versioned by the row's
 * {@code version}: the levels the exits read are these columns (D-119). The levels the plan was made with stay on the
 * plan, which never changes once executed.
 *
 * <p>Only the columns written so far are mapped; the exit (T-048b) and the learning data of D-111 (T-048c) add theirs.
 *
 * <p>Rule: BR-34, BR-36, BR-40; D-78, D-97, D-119.
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

    @Enumerated(EnumType.STRING)
    @Column(name = "trade_status", nullable = false, length = 32)
    private PositionStatus status;

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
        record.quantity = opening.quantity();
        // To the microsecond, like the column, so the record reads back as it was written.
        record.entryTime = opening.entryTime().truncatedTo(ChronoUnit.MICROS);
        record.entryLiquidity = opening.entryLiquidity();
        record.entryFee = opening.entryFee();
        record.status = PositionStatus.OPEN;
        return record;
    }

    /** The position as the plan detail shows it. Only a SIMULATED record has a plan, and only it is asked. */
    public RecordedPosition toPosition() {
        return new RecordedPosition(planId, status, entryPrice, quantity, entryTime, entryFee);
    }
}
