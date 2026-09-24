package com.cryptopilot.market.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.PairFilters;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.Getter;

/**
 * A tradable pair — {@code BTCUSDT} — with its Spot and futures markets side by side.
 *
 * <h2>One row per symbol, not per market</h2>
 *
 * <p>The logical model's {@code CRYPTO_PAIR} holds both markets of a symbol in one row: a switch and the
 * three filters for Spot, the same for futures. So {@code BTCUSDT} on Spot and {@code BTCUSDT} on futures
 * are one pair with two markets, never two rows, and the symbol alone is the natural key
 * ({@code uq_crypto_pair_symbol}). The market becomes part of the identity one level down, in everything
 * that depends on a pair: a candle, a snapshot, a plan and a watch are keyed or constrained by
 * {@code (pair_id, market_type)}.
 *
 * <h2>What it holds and what it does not</h2>
 *
 * <p>Reference data an administrator curates (BR-07, SCR-37) and the synchronisation of NSF-01 refreshes
 * daily: the coins, the switches, the filters, the leverage ceiling, the display order. Nothing that
 * changes every second — the last price, the 24-hour statistics — lives here: those are in the Redis
 * cache and the snapshot tables (TECHNICAL_DESIGN 5.6, 6), so this row is written once a day at most and
 * its indexes stay small.
 *
 * <p>The coins are held by identifier, not by association (D-21's rule for the identity tables applies
 * here for the same reasons: nothing navigates from a pair to its coin often enough to justify a fetch
 * strategy).
 *
 * <p>Rule: BR-07, BR-23, BR-30; SRS 3.1.5, UC-45; TECHNICAL_DESIGN 5.4 and 6.
 *
 * <p>Reference: Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley, ch. 5 (entities and
 * value objects: the pair is an entity, its filters a value object).
 * <p>Reference: Codd, E. F. (1970). <i>A Relational Model of Data for Large Shared Data Banks</i>.
 * Communications of the ACM 13(6) (a relation is identified by a key; the natural key here is the symbol).
 */
@Getter
@Entity
@Table(name = "crypto_pair")
@AttributeOverride(name = "id", column = @Column(name = "pair_id", nullable = false, updatable = false))
public class CryptoPair extends BaseEntity {

    /** Longest symbol the column holds ({@code varchar(32)}). */
    static final int SYMBOL_LENGTH = 32;

    /** The coin bought and sold. */
    @Column(name = "base_coin_id", nullable = false, updatable = false)
    private UUID baseCoinId;

    /** The coin the price is quoted in. */
    @Column(name = "quote_coin_id", nullable = false, updatable = false)
    private UUID quoteCoinId;

    /** The symbol, e.g. {@code BTCUSDT}; unique across all pairs. */
    @Column(name = "symbol", nullable = false, length = SYMBOL_LENGTH, updatable = false)
    private String symbol;

    /** Whether an administrator has enabled the Spot market. */
    @Column(name = "is_spot_enabled", nullable = false)
    private boolean spotEnabled;

    /** Whether an administrator has enabled the futures market. */
    @Column(name = "is_futures_enabled", nullable = false)
    private boolean futuresEnabled;

    @Column(name = "spot_tick_size", precision = 28, scale = 12)
    private BigDecimal spotTickSize;

    @Column(name = "spot_step_size", precision = 28, scale = 12)
    private BigDecimal spotStepSize;

    @Column(name = "spot_min_notional", precision = 28, scale = 8)
    private BigDecimal spotMinNotional;

    @Column(name = "futures_tick_size", precision = 28, scale = 12)
    private BigDecimal futuresTickSize;

    @Column(name = "futures_step_size", precision = 28, scale = 12)
    private BigDecimal futuresStepSize;

    @Column(name = "futures_min_notional", precision = 28, scale = 8)
    private BigDecimal futuresMinNotional;

    /** The highest leverage an administrator allows, or {@code null} when futures is not enabled. */
    @Column(name = "max_leverage")
    private Integer maxLeverage;

    /** The administrator's switch for the pair as a whole (BR-07). */
    @Enumerated(EnumType.STRING)
    @Column(name = "pair_status", nullable = false, length = 32)
    private PairStatus pairStatus;

    /** Where the pair appears in lists; lower first. */
    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    /** The exchange's Spot trading status, normalized, or {@code null} if never listed on Spot (Q-15). */
    @Enumerated(EnumType.STRING)
    @Column(name = "spot_exchange_status", length = 32)
    private ExchangeStatus spotExchangeStatus;

    /** Binance's Spot status text exactly as sent, or {@code null}; stored, never interpreted. */
    @Column(name = "spot_exchange_status_raw")
    private String spotExchangeStatusRaw;

    /** The exchange's futures trading status, normalized, or {@code null} if never listed on futures. */
    @Enumerated(EnumType.STRING)
    @Column(name = "futures_exchange_status", length = 32)
    private ExchangeStatus futuresExchangeStatus;

    /** Binance's futures status text exactly as sent, or {@code null}; stored, never interpreted. */
    @Column(name = "futures_exchange_status_raw")
    private String futuresExchangeStatusRaw;

    /** When NSF-01 last reached this pair, or {@code null} before the first synchronisation. */
    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    /** For JPA only. */
    protected CryptoPair() {}

    private CryptoPair(UUID baseCoinId, UUID quoteCoinId, String symbol) {
        this.baseCoinId = Objects.requireNonNull(baseCoinId, "baseCoinId must not be null");
        this.quoteCoinId = Objects.requireNonNull(quoteCoinId, "quoteCoinId must not be null");
        if (baseCoinId.equals(quoteCoinId)) {
            throw new IllegalArgumentException("a pair needs two different coins");
        }
        this.symbol = PairSymbols.require(symbol, "symbol", SYMBOL_LENGTH);
        this.pairStatus = PairStatus.INACTIVE;
    }

    /**
     * A pair as it is first recorded: known to the system, and not yet available to anybody.
     *
     * <p>INACTIVE with both markets off, because BR-07 makes a pair visible only once an administrator
     * enables it (SCR-37); a pair that appeared as soon as the exchange listed it would be the exchange
     * deciding what the system shows. No filters yet: they arrive from the exchange per market.
     *
     * <p>Rule: BR-07; SRS UC-45.
     */
    public static CryptoPair register(UUID baseCoinId, UUID quoteCoinId, String symbol) {
        return new CryptoPair(baseCoinId, quoteCoinId, symbol);
    }

    /**
     * Replaces the trading rules of one market with the exchange's current ones (NSF-01). The other
     * market is left as it was: Spot and futures have separate filters on the exchange.
     *
     * <p>Rule: NSF-01; BR-23, BR-30.
     */
    public void applyFilters(MarketType market, PairFilters filters) {
        Objects.requireNonNull(filters, "filters must not be null");
        switch (Objects.requireNonNull(market, "market must not be null")) {
            case SPOT -> {
                this.spotTickSize = filters.tickSize();
                this.spotStepSize = filters.stepSize();
                this.spotMinNotional = filters.minNotional();
            }
            case FUTURES -> {
                this.futuresTickSize = filters.tickSize();
                this.futuresStepSize = filters.stepSize();
                this.futuresMinNotional = filters.minNotional();
            }
        }
    }

    /**
     * The trading rules of one market, or empty when the exchange has not supplied them yet. A plan cannot
     * be sized without all three, so a market with any of them missing has none.
     */
    public Optional<PairFilters> filters(MarketType market) {
        return switch (Objects.requireNonNull(market, "market must not be null")) {
            case SPOT -> filtersOf(spotTickSize, spotStepSize, spotMinNotional);
            case FUTURES -> filtersOf(futuresTickSize, futuresStepSize, futuresMinNotional);
        };
    }

    /**
     * Whether this pair is available on this market: the pair is ACTIVE and the market's switch is on
     * (BR-07). Only then is it shown, collected and offered for alerts and plans.
     *
     * <p>Rule: BR-07.
     */
    public boolean isEnabledOn(MarketType market) {
        boolean marketSwitch =
                switch (Objects.requireNonNull(market, "market must not be null")) {
                    case SPOT -> spotEnabled;
                    case FUTURES -> futuresEnabled;
                };
        return pairStatus == PairStatus.ACTIVE && marketSwitch;
    }

    /** The exchange's status of one market, or {@code null} when the pair has never been listed on it. */
    public ExchangeStatus exchangeStatus(MarketType market) {
        return switch (Objects.requireNonNull(market, "market must not be null")) {
            case SPOT -> spotExchangeStatus;
            case FUTURES -> futuresExchangeStatus;
        };
    }

    /** Binance's own status text of one market, or {@code null}. */
    public String exchangeStatusRaw(MarketType market) {
        return switch (Objects.requireNonNull(market, "market must not be null")) {
            case SPOT -> spotExchangeStatusRaw;
            case FUTURES -> futuresExchangeStatusRaw;
        };
    }

    /**
     * Records what the exchange says about one market: the normalized status and Binance's own text beside
     * it, or {@code null} text when the market no longer appears at all (DELISTED). The other market is left
     * as it was.
     *
     * <p>Rule: NSF-01; Q-15.
     */
    public void recordExchangeStatus(MarketType market, ExchangeStatus status, String rawStatus) {
        Objects.requireNonNull(status, "status must not be null");
        switch (Objects.requireNonNull(market, "market must not be null")) {
            case SPOT -> {
                this.spotExchangeStatus = status;
                this.spotExchangeStatusRaw = rawStatus;
            }
            case FUTURES -> {
                this.futuresExchangeStatus = status;
                this.futuresExchangeStatusRaw = rawStatus;
            }
        }
    }

    /** Records that NSF-01 reached this pair at this instant, from the injected clock. */
    public void markSynced(Instant at) {
        this.lastSyncedAt = Objects.requireNonNull(at, "at must not be null");
    }

    private static Optional<PairFilters> filtersOf(BigDecimal tick, BigDecimal step, BigDecimal minNotional) {
        if (tick == null || step == null || minNotional == null) {
            return Optional.empty();
        }
        return Optional.of(new PairFilters(tick, step, minNotional));
    }
}
