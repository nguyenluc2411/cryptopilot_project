package com.cryptopilot.trading.model;

import com.cryptopilot.trading.model.enums.LiquidityRole;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * The simulated fee rates of one market, read from the system settings.
 *
 * <p>Rule: BR-36; D-97; TECHNICAL_DESIGN 7.8.
 *
 * @param maker the maker rate: fills of orders that rested and were then reached (a LIMIT entry reached after its
 *     activation, a take profit)
 * @param taker the taker rate: fills that executed against the market (MARKET and STOP entries, a LIMIT entry already
 *     reached at activation, a stop loss, a manual close)
 * @param liquidationClearance the clearance fee rate of a liquidation; {@code null} for Spot, which has none
 */
public record FeeSchedule(BigDecimal maker, BigDecimal taker, BigDecimal liquidationClearance) {

    public FeeSchedule {
        Objects.requireNonNull(maker, "maker");
        Objects.requireNonNull(taker, "taker");
    }

    /** The rate of a fill with this liquidity role. */
    public BigDecimal rate(LiquidityRole role) {
        return Objects.requireNonNull(role, "role") == LiquidityRole.MAKER ? maker : taker;
    }

    /**
     * The fee of one fill at full precision: its notional, price × quantity, times the rate of its liquidity role.
     * Leverage does not reduce it. Every simulated fee and every fee estimate is this product.
     *
     * <p>Rule: BR-36; D-97; D-111 E1; TECHNICAL_DESIGN 7.8.
     * <p>Reference: Binance FAQ, <i>Binance Futures Fee Structure &amp; Fee Calculations</i> (fee = notional × rate).
     */
    public BigDecimal fee(LiquidityRole role, BigDecimal price, BigDecimal quantity) {
        return price.multiply(quantity).multiply(rate(role));
    }
}
