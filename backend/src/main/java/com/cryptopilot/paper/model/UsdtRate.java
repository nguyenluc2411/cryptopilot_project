package com.cryptopilot.paper.model;

import com.cryptopilot.paper.model.enums.UsdtRateSource;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * What one unit of a fill's quote coin was worth in USDT when the fill executed, and where that came from.
 *
 * <p>Rule: TR-13; D-96.
 *
 * @param rate the USDT value of one unit of the quote coin; {@code null} only when the source is {@code NONE}
 * @param source where the rate came from
 */
public record UsdtRate(BigDecimal rate, UsdtRateSource source) {

    /** The rate of USDT itself. */
    public static final UsdtRate ONE = new UsdtRate(BigDecimal.ONE, UsdtRateSource.ONE);

    /** No rate known. */
    public static final UsdtRate NONE = new UsdtRate(null, UsdtRateSource.NONE);

    public UsdtRate {
        Objects.requireNonNull(source, "source");
        if ((source == UsdtRateSource.NONE) != (rate == null) || (rate != null && rate.signum() <= 0)) {
            throw new IllegalArgumentException(
                    "a rate is positive and given unless its source is NONE: " + rate + " from " + source);
        }
    }

    /** Whether a rate is known. */
    public boolean known() {
        return rate != null;
    }

    /** The USDT value of {@code amount} of the quote coin; only for a known rate. */
    public BigDecimal valueOf(BigDecimal amount) {
        if (rate == null) {
            throw new IllegalStateException("no USDT rate is known");
        }
        return amount.multiply(rate);
    }
}
