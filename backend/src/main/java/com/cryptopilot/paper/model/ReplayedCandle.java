package com.cryptopilot.paper.model;

import com.cryptopilot.paper.model.enums.FillSource;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * The replayed closed 1-minute candle a fill was decided by: what the market did at that minute, which a pending stop
 * of the order's group is judged against when the fill activates it, instead of the live price of now (TR-10).
 *
 * <p>Rule: TR-09, TR-10; Q-T6; D-78.
 *
 * <p>Reference: Akidau, T. et al. (2015). The Dataflow Model. <i>PVLDB</i>, 8(12), 1792-1803 (event time, not
 * processing time).
 *
 * @param open its first price; {@code null} when not known
 * @param low its lowest price
 * @param high its highest price
 */
public record ReplayedCandle(BigDecimal open, BigDecimal low, BigDecimal high) {

    public ReplayedCandle {
        Objects.requireNonNull(low, "low");
        Objects.requireNonNull(high, "high");
    }

    /** The candle of a replayed range; {@code null} for a live one. */
    public static ReplayedCandle of(PriceRange range) {
        return range.source() == FillSource.REPLAY ? new ReplayedCandle(range.open(), range.low(), range.high()) : null;
    }
}
