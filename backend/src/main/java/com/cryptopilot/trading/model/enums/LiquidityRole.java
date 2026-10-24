package com.cryptopilot.trading.model.enums;

/**
 * Whether a fill supplied or took liquidity, which decides its fee rate. The names are the values
 * {@code trading_plan.fill_liquidity} and {@code trading_journal.entry_liquidity} accept.
 *
 * <p>Rule: BR-36; D-111 (E1).
 *
 * <p>Reference: Harris, L. (2003). <i>Trading and Exchanges: Market Microstructure for Practitioners</i>. Oxford
 * University Press, ch. 6 (liquidity suppliers and demanders).
 */
public enum LiquidityRole {

    /** The order rested and was then reached. */
    MAKER,

    /** The order executed against the market. */
    TAKER
}
