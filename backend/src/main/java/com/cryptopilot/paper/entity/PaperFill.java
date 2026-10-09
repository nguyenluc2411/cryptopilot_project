package com.cryptopilot.paper.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.model.enums.Liquidity;
import com.cryptopilot.paper.model.enums.OrderSide;
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
 * <p>The position side and realised profit of a Futures fill are left unmapped until the Futures stage of TR-02.
 *
 * <p>Rule: TR-02; Q-T6 (the source says whether a live update or a replayed candle decided it).
 */
@Getter
@Entity
@Table(name = "paper_fill")
@AttributeOverride(name = "id", column = @Column(name = "fill_id", nullable = false, updatable = false))
public class PaperFill extends BaseEntity {

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

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

    /** For JPA only. */
    protected PaperFill() {}

    private PaperFill(PaperOrder order, Execution execution) {
        this.orderId = order.getId();
        this.accountId = order.getAccountId();
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
    }

    /** The fill of {@code order} the execution describes. */
    public static PaperFill of(PaperOrder order, Execution execution) {
        return new PaperFill(Objects.requireNonNull(order, "order"), Objects.requireNonNull(execution, "execution"));
    }

    /**
     * The figures of one execution, checked as {@code ck_paper_fill_amounts} checks them.
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
