package com.cryptopilot.trading.calculator;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.FundingHistory;
import com.cryptopilot.market.SettledFunding;
import com.cryptopilot.trading.model.FundingFee;
import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The funding of a Futures position: the signed sum of what it paid or received at each settlement while it was
 * open, computed from the stored settlements whenever it is needed instead of booked at each settlement.
 *
 * <p>Rule: BR-37; NSF-08 as realized by A-05; D-97 rule 3; TECHNICAL_DESIGN 7.8.
 */
public final class FundingCalculator {

    private FundingCalculator() {}

    /**
     * The funding of a position open from {@code entryTime} to {@code exitTime}: the sum over the settlements with
     * {@code entryTime < fundingTime ≤ exitTime} of {@link #settlementAmount}, kept at full precision and rounded once
     * to the stored scale. A position opened exactly at a settlement takes no part in it; one closed exactly at a
     * settlement does.
     *
     * <p>When a settlement the exchange announced in that period is not in the history, the result is incomplete and
     * carries no amount: a missing rate is never replaced by a guess or by zero. Computing again once the row is
     * stored gives the full figure, and the same figure on every later call.
     *
     * <p>Rule: BR-37; A-05; D-97 rule 3; TECHNICAL_DESIGN 7.8.
     * <p>Reference: Binance FAQ, <i>Introduction to Binance Futures Funding Rates</i> (only positions open at a
     * funding timestamp pay or receive it).
     *
     * @param direction LONG or SHORT
     * @param quantity the position size, above 0
     * @param entryTime when the position was filled
     * @param exitTime when it closed, or the present for an open position
     * @param history the pair's settlements and announced settlement instants covering the period
     */
    public static FundingFee fundingFee(
            Direction direction, BigDecimal quantity, Instant entryTime, Instant exitTime, FundingHistory history) {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(entryTime, "entryTime");
        Objects.requireNonNull(exitTime, "exitTime");
        Objects.requireNonNull(history, "history");
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("quantity must be above 0, was " + quantity);
        }
        if (exitTime.isBefore(entryTime)) {
            throw new IllegalArgumentException("exit time " + exitTime + " is before entry time " + entryTime);
        }
        BigDecimal sum = BigDecimal.ZERO;
        Set<Instant> settled = new HashSet<>();
        for (SettledFunding settlement : history.settlements()) {
            if (inPeriod(settlement.fundingTime(), entryTime, exitTime) && settled.add(settlement.fundingTime())) {
                sum = sum.add(settlementAmount(direction, quantity, settlement.markPrice(), settlement.fundingRate()));
            }
        }
        List<Instant> missing = history.scheduledTimes().stream()
                .filter(time -> inPeriod(time, entryTime, exitTime) && !settled.contains(time))
                .distinct()
                .sorted()
                .toList();
        return missing.isEmpty() ? FundingFee.complete(Rounding.toAmount(sum)) : FundingFee.incomplete(missing);
    }

    /**
     * What one settlement moves for the position, unrounded: {@code −d × quantity × markPrice × rate} with
     * {@code d} = +1 for LONG and −1 for SHORT. A positive rate makes a LONG pay (negative) and a SHORT receive
     * (positive); a negative rate the opposite.
     *
     * <p>Rule: BR-37; D-97 rule 3; TECHNICAL_DESIGN 7.8.
     * <p>Reference: Binance FAQ, <i>Introduction to Binance Futures Funding Rates</i> (funding = mark price × position
     * size × funding rate; when the rate is positive, longs pay shorts).
     */
    public static BigDecimal settlementAmount(
            Direction direction, BigDecimal quantity, BigDecimal markPrice, BigDecimal fundingRate) {
        BigDecimal paidByLong = quantity.multiply(markPrice).multiply(fundingRate);
        return direction == Direction.LONG ? paidByLong.negate() : paidByLong;
    }

    // entry < t ≤ exit (TECHNICAL_DESIGN 7.8).
    private static boolean inPeriod(Instant fundingTime, Instant entryTime, Instant exitTime) {
        return fundingTime.isAfter(entryTime) && !fundingTime.isAfter(exitTime);
    }
}
