package com.cryptopilot.trading.model;

import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * How a plan's entry is expected to fill, as the risk panel estimates its costs: its liquidity role, the price before
 * slippage and the slippage in force.
 *
 * <p>Rule: BR-29, BR-33, BR-36; D-111 E1, E5; D-116.
 *
 * @param entryType LIMIT, MARKET or STOP
 * @param liquidity TAKER for MARKET and STOP and for a LIMIT the current last price already reaches; MAKER for a
 *     LIMIT that rests
 * @param reference the price a taker entry slips from: the last price for MARKET and for a marketable LIMIT, the stop
 *     price for STOP; {@code null} for a maker entry
 * @param slippageBps {@code SLIPPAGE_BPS} as read for this calculation
 */
public record EntryExpectation(EntryType entryType, LiquidityRole liquidity, BigDecimal reference, int slippageBps) {

    public EntryExpectation {
        Objects.requireNonNull(entryType, "entryType");
        Objects.requireNonNull(liquidity, "liquidity");
        if ((liquidity == LiquidityRole.TAKER) != (reference != null)) {
            throw new IllegalArgumentException("a taker entry has a reference price and a maker entry has none");
        }
        if (slippageBps < 0) {
            throw new IllegalArgumentException("slippage must not be negative, was " + slippageBps);
        }
    }
}
