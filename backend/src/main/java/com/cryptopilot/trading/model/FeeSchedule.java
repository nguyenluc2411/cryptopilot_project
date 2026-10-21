package com.cryptopilot.trading.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The simulated fee rates of one market, read from the system settings.
 *
 * <p>Rule: BR-36; D-97; TECHNICAL_DESIGN 7.8.
 *
 * @param maker the maker rate: LIMIT entries and take profit exits
 * @param taker the taker rate: MARKET entries, stop loss and manual exits
 * @param liquidationClearance the clearance fee rate of a liquidation; {@code null} for Spot, which has none
 */
public record FeeSchedule(BigDecimal maker, BigDecimal taker, BigDecimal liquidationClearance) {

    public FeeSchedule {
        Objects.requireNonNull(maker, "maker");
        Objects.requireNonNull(taker, "taker");
    }
}
