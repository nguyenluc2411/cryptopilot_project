package com.cryptopilot.trading.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What closing an open position at one price and instant stores: the exit price after any slippage, the exit time at
 * the stored precision, the exit fee alone, the stored amounts and the funding settlements known to be missing.
 *
 * <p>Rule: BR-35, BR-36, BR-37, BR-38; D-97.
 *
 * @param exitPrice the exit fill price
 * @param exitTime when the exit counts, to the microsecond
 * @param exitFee the fee of the exit fill, or the clearance fee of a liquidation
 * @param amounts realized P/L, entry plus exit fee, and the funding known so far
 * @param missingFundingTimes the settlement instants known to be missing, oldest first
 */
public record ClosingAmounts(
        BigDecimal exitPrice,
        Instant exitTime,
        BigDecimal exitFee,
        TradeAmounts amounts,
        List<Instant> missingFundingTimes) {

    public ClosingAmounts {
        Objects.requireNonNull(exitPrice, "exitPrice");
        Objects.requireNonNull(exitTime, "exitTime");
        Objects.requireNonNull(exitFee, "exitFee");
        Objects.requireNonNull(amounts, "amounts");
        missingFundingTimes = List.copyOf(missingFundingTimes);
    }
}
