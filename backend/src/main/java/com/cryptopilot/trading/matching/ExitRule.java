package com.cryptopilot.trading.matching;

import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.LiquidityRole;

/**
 * How a plan's exit pays, as the plan's panel and its result estimate it: a take profit rests and is then reached, a
 * stop loss and a manual close execute against the market. The exits themselves are the demo account's paper orders
 * since RF-33 (D-117).
 *
 * <p>Rule: BR-36; D-97; D-111 E1; D-117.
 *
 * <p>Reference: Harris, L. (2003). <i>Trading and Exchanges: Market Microstructure for Practitioners</i>. Oxford
 * University Press, ch. 4 (a resting limit order trades at its own price; a stop order becomes a market order once
 * triggered).
 */
public final class ExitRule {

    private ExitRule() {}

    /**
     * The liquidity role of an exit: a take profit rested and was then reached, so it is maker; a stop loss and a
     * manual close executed against the market, so they are taker. A liquidation has no role: it pays the clearance
     * fee instead.
     *
     * @throws IllegalArgumentException for a liquidation
     */
    public static LiquidityRole liquidity(CloseReason reason) {
        return switch (reason) {
            case TAKE_PROFIT -> LiquidityRole.MAKER;
            case STOP_LOSS, MANUAL -> LiquidityRole.TAKER;
            case LIQUIDATION -> throw new IllegalArgumentException("a liquidation pays the clearance fee, not a rate");
        };
    }
}
