package com.cryptopilot.trading.model;

import com.cryptopilot.trading.model.enums.LiquidityRole;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An entry the matching engine has decided to fill: its liquidity role, the price the fill rule gives and the time the
 * fill is recorded with. A MAKER fill is stored at that price; a TAKER fill is moved by the slippage against the Trader
 * when it is stored (BR-36; D-111 E5).
 *
 * <p>Rule: BR-33, BR-36, NSF-07; D-78 (the time is the open time of the candle that reached the entry); D-111 (E1).
 *
 * @param entry the plan as the engine tracked it
 * @param liquidity MAKER for a resting LIMIT reached by the price, TAKER for a triggered STOP
 * @param price the price before slippage: a LIMIT's own price, also through a gap (E7); a STOP's stop price, or the
 *     candle's open when the candle opened past it (a gap)
 * @param executedAt when the plan counts as filled
 */
public record Fill(TrackedEntry entry, LiquidityRole liquidity, BigDecimal price, Instant executedAt)
        implements PlanOutcome {

    public Fill {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(liquidity, "liquidity");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(executedAt, "executedAt");
    }

    @Override
    public UUID planId() {
        return entry.planId();
    }

    @Override
    public Instant at() {
        return executedAt;
    }
}
