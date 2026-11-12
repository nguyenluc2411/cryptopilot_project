package com.cryptopilot.trading;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * The most adverse and the most favourable last price an open simulated position has seen, as the matching engine knows
 * them. The journal merges them with what it stored before, so a stored extreme only ever widens, also after a restart
 * that rebuilt the engine from the fill price.
 *
 * <p>Rule: BR-70; D-111 (M4), D-112 R5.
 *
 * <p>Reference: Sweeney, J. (1996). <i>Maximum Adverse Excursion: Analyzing Price Fluctuations for Trading
 * Management</i>. Wiley.
 *
 * @param positionId the journal record
 * @param adverse the lowest price of a LONG, the highest of a SHORT
 * @param favorable the highest price of a LONG, the lowest of a SHORT
 */
public record PositionExcursion(UUID positionId, BigDecimal adverse, BigDecimal favorable) {

    public PositionExcursion {
        Objects.requireNonNull(positionId, "positionId");
        Objects.requireNonNull(adverse, "adverse");
        Objects.requireNonNull(favorable, "favorable");
    }
}
