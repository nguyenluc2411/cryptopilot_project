package com.cryptopilot.trading.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The true breakeven stop of an open position and whether it may still move because a funding settlement is missing.
 *
 * <p>Rule: BR-37, BR-38; D-124.
 *
 * @param price the stop price on the tick at which a stop-out nets zero or more
 * @param provisional whether the funding it covers may be incomplete
 */
public record Breakeven(BigDecimal price, boolean provisional) {

    public Breakeven {
        Objects.requireNonNull(price, "price");
    }
}
