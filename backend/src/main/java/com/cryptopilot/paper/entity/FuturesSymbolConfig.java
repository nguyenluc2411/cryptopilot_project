package com.cryptopilot.paper.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.paper.model.enums.MarginType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

/**
 * The leverage and margin type a Trader chose for one Futures pair, as the exchange keeps them per symbol. A pair
 * without a row trades at the default.
 *
 * <p>Rule: TR-14; D-127.
 */
@Getter
@Entity
@Table(name = "futures_symbol_config")
@AttributeOverride(name = "id", column = @Column(name = "symbol_config_id", nullable = false, updatable = false))
public class FuturesSymbolConfig extends BaseEntity {

    /** The leverage a pair trades at before the Trader chooses one, as on the exchange. */
    public static final int DEFAULT_LEVERAGE = 20;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "pair_id", nullable = false, updatable = false)
    private UUID pairId;

    @Column(name = "leverage", nullable = false)
    private int leverage;

    @Enumerated(EnumType.STRING)
    @Column(name = "margin_type", nullable = false, length = 32)
    private MarginType marginType;

    /** For JPA only. */
    protected FuturesSymbolConfig() {}

    private FuturesSymbolConfig(UUID userId, UUID pairId, int leverage, MarginType marginType) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.pairId = Objects.requireNonNull(pairId, "pairId must not be null");
        this.leverage = leverage;
        this.marginType = Objects.requireNonNull(marginType, "marginType must not be null");
    }

    /**
     * The settings of a pair the Trader has not configured: 20x, or the pair's highest leverage when lower, and
     * CROSSED.
     *
     * @param maxLeverage the highest leverage of the pair, bracket 1's
     */
    public static FuturesSymbolConfig defaults(UUID userId, UUID pairId, int maxLeverage) {
        return new FuturesSymbolConfig(userId, pairId, Math.min(DEFAULT_LEVERAGE, maxLeverage), MarginType.CROSSED);
    }

    /** Sets the leverage; the caller has checked it against the pair and the position. */
    public void changeLeverage(int leverage) {
        if (leverage < 1) {
            throw new IllegalArgumentException("leverage must be at least 1, was " + leverage);
        }
        this.leverage = leverage;
    }

    /** Sets the margin type; the caller has checked that the pair has no position and no open order. */
    public void changeMarginType(MarginType marginType) {
        this.marginType = Objects.requireNonNull(marginType, "marginType must not be null");
    }
}
