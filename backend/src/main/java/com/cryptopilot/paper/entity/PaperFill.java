package com.cryptopilot.paper.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.model.UsdtRate;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.model.enums.Liquidity;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.PositionSide;
import com.cryptopilot.paper.model.enums.UsdtRateSource;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

/**
 * One execution of a paper order, never edited and never deleted: the trade history of the trading screens. The
 * balance changes it makes are ledger entries that point back at it.
 *
 * <p>A Futures fill belongs to exactly one position, with the position side of its order and the profit or loss it
 * realised; a fill that reverses a one-way position is stored as two, one closing the old position and one opening
 * the new (TR-16).
 *
 * <p>Rule: TR-02, TR-16; Q-T6 (the source says whether a live update or a replayed candle decided it).
 */
@Getter
@Entity
@Table(name = "order_fill")
@AttributeOverride(name = "id", column = @Column(name = "fill_id", nullable = false, updatable = false))
public class PaperFill extends BaseEntity {

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "pair_id", nullable = false, updatable = false)
    private UUID pairId;

    @Enumerated(EnumType.STRING)
    @Column(name = "market_type", nullable = false, length = 32, updatable = false)
    private MarketType marketType;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 32, updatable = false)
    private OrderSide side;

    @Column(name = "fill_price", nullable = false, precision = 28, scale = 12, updatable = false)
    private BigDecimal fillPrice;

    @Column(name = "fill_quantity", nullable = false, precision = 28, scale = 12, updatable = false)
    private BigDecimal fillQuantity;

    /** What the fill cost (a buy) or brought (a sell), in the quote asset, before the fee. */
    @Column(name = "quote_amount", nullable = false, precision = 28, scale = 8, updatable = false)
    private BigDecimal quoteAmount;

    @Column(name = "fee_amount", nullable = false, precision = 28, scale = 8, updatable = false)
    private BigDecimal feeAmount;

    /** The coin the fee was taken in: the one the fill brought in. */
    @Column(name = "fee_coin_id", nullable = false, updatable = false)
    private UUID feeCoinId;

    @Enumerated(EnumType.STRING)
    @Column(name = "liquidity", nullable = false, length = 32, updatable = false)
    private Liquidity liquidity;

    @Enumerated(EnumType.STRING)
    @Column(name = "fill_source", nullable = false, length = 32, updatable = false)
    private FillSource fillSource;

    /** When the trade happened: the placement for an order that executed on arrival, else its candle's open time. */
    @Column(name = "traded_at", nullable = false, updatable = false)
    private Instant tradedAt;

    @Column(name = "source_time")
    private Instant sourceTime;

    /** The USDT value of one unit of the quote coin the fill was valued at (D-96); {@code null} when none was known. */
    @Column(name = "quote_usdt_price", precision = 28, scale = 12, updatable = false)
    private BigDecimal quoteUsdtPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "quote_usdt_price_source", length = 32, updatable = false)
    private UsdtRateSource quoteUsdtPriceSource;

    /** The position side of a Futures fill's order; {@code null} on Spot. */
    @Enumerated(EnumType.STRING)
    @Column(name = "position_side", length = 32, updatable = false)
    private PositionSide positionSide;

    /** The position a Futures fill opened, increased, reduced or closed; {@code null} on Spot. */
    @Column(name = "position_id", updatable = false)
    private UUID positionId;

    /** What a Futures fill realised, zero when it opened or increased; {@code null} on Spot. */
    @Column(name = "realized_pnl_amount", precision = 28, scale = 8, updatable = false)
    private BigDecimal realizedPnlAmount;

    /** For JPA only. */
    protected PaperFill() {}

    private PaperFill(PaperOrder order, Execution execution, UsdtRate quoteUsdt) {
        this.orderId = order.getId();
        this.userId = order.getUserId();
        this.pairId = order.getPairId();
        this.marketType = order.getMarketType();
        this.side = order.getSide();
        this.fillPrice = execution.price();
        this.fillQuantity = execution.quantity();
        this.quoteAmount = execution.quoteAmount();
        this.feeAmount = execution.feeAmount();
        this.feeCoinId = execution.feeCoinId();
        this.liquidity = execution.liquidity();
        this.fillSource = execution.source();
        this.tradedAt = execution.tradedAt();
        if (quoteUsdt != null) {
            this.quoteUsdtPrice = quoteUsdt.rate();
            this.quoteUsdtPriceSource = quoteUsdt.source();
        }
    }

    /** The fill of {@code order} the execution describes, with no USDT rate recorded. */
    public static PaperFill of(PaperOrder order, Execution execution) {
        return of(order, execution, null);
    }

    /** The fill of {@code order} the execution describes, valued at {@code quoteUsdt} (D-96). */
    public static PaperFill of(PaperOrder order, Execution execution, UsdtRate quoteUsdt) {
        return new PaperFill(
                Objects.requireNonNull(order, "order"), Objects.requireNonNull(execution, "execution"), quoteUsdt);
    }

    /**
     * A Futures fill a replay applied takes the price of a candle earlier than when it is applied (plan 9.5): it is
     * dated when applied, and keeps the candle's time here, to show at what time's price it filled. Nothing for a live
     * fill, which leaves {@code sourceTime} {@code null}.
     *
     * @param candleTime the time of the candle whose price a replay took, or {@code null} for a live fill
     * @return this fill
     */
    public PaperFill takenFrom(Instant candleTime) {
        if (candleTime != null) {
            if (fillSource != FillSource.REPLAY) {
                throw new IllegalStateException("only a replayed fill takes the price of a candle");
            }
            sourceTime = candleTime;
        }
        return this;
    }

    /**
     * A fill of a Futures order: part or all of an execution, on one position.
     *
     * @param positionId the position it acts on
     * @param realizedPnl what it realised, zero when it opened or increased the position
     */
    public static PaperFill futures(PaperOrder order, Execution execution, UUID positionId, BigDecimal realizedPnl) {
        if (order.getMarketType() != MarketType.FUTURES) {
            throw new IllegalArgumentException("order " + order.getId() + " is not a Futures order");
        }
        PaperFill fill = of(order, execution, null);
        fill.positionSide = order.getPositionSide();
        fill.positionId = Objects.requireNonNull(positionId, "positionId");
        fill.realizedPnlAmount = Objects.requireNonNull(realizedPnl, "realizedPnl");
        return fill;
    }

    /**
     * The figures of one execution, checked as {@code ck_order_fill_amounts} checks them.
     *
     * @param price the price it executed at
     * @param quantity the base quantity
     * @param quoteAmount what it cost or brought in the quote asset, before the fee
     * @param feeAmount the commission, in {@code feeCoinId}; zero or more
     * @param feeCoinId the coin the commission is taken in
     * @param liquidity maker or taker
     * @param source live or replayed
     * @param tradedAt when it happened
     */
    public record Execution(
            BigDecimal price,
            BigDecimal quantity,
            BigDecimal quoteAmount,
            BigDecimal feeAmount,
            UUID feeCoinId,
            Liquidity liquidity,
            FillSource source,
            Instant tradedAt) {

        public Execution {
            Objects.requireNonNull(feeCoinId, "feeCoinId");
            Objects.requireNonNull(liquidity, "liquidity");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(tradedAt, "tradedAt");
            if (price.signum() <= 0 || quantity.signum() <= 0 || quoteAmount.signum() <= 0 || feeAmount.signum() < 0) {
                throw new IllegalArgumentException("an execution has a positive price, quantity and quote amount and"
                        + " no negative fee: " + price + ", " + quantity + ", " + quoteAmount + ", " + feeAmount);
            }
        }
    }
}
