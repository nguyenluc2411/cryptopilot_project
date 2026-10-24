package com.cryptopilot.trading.calculator;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.FundingAnnouncement;
import com.cryptopilot.market.FundingHistory;
import com.cryptopilot.market.SettledFunding;
import com.cryptopilot.market.SnapshotGap;
import com.cryptopilot.trading.model.FundingFee;
import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The funding of a Futures position: the signed sum of what it paid or received at each settlement while it was
 * open, computed from the stored settlements whenever it is needed instead of booked at each settlement, with a check
 * that no settlement of the period is missing.
 *
 * <p>Rule: BR-11, BR-37; NSF-08 as realized by A-05; D-97 rule 3; TECHNICAL_DESIGN 7.8.
 */
public final class FundingCalculator {

    private FundingCalculator() {}

    /**
     * The funding of a position open from {@code entryTime} to {@code exitTime}: the sum over the stored settlements
     * with {@code entryTime < fundingTime ≤ exitTime} of {@link #settlementAmount}, kept at full precision and rounded
     * once to the stored scale. A position opened exactly at a settlement takes no part in it; one closed exactly at a
     * settlement does.
     *
     * <p>The result is incomplete, with the known sum as its amount, when a settlement of the period may be missing:
     * a missing rate is never replaced by a guess or by zero, and computing again once the row is stored gives the
     * full figure. A settlement is missing when
     * <ul>
     *   <li>a snapshot announced it and no row is stored for it; or
     *   <li>it falls in a snapshot gap inside the period that is longer than one funding interval, where no snapshot
     *       may have announced it, and no row is stored for it. The instants of such a gap are the pair's funding
     *       times stepped by the interval from the nearest known one. A gap of at most one interval hides nothing:
     *       the snapshot before it announces the only settlement it can contain.
     * </ul>
     * The interval is the distance between two consecutive announced instants whose announcing snapshots have no gap
     * between them, the shortest such distance; failing that, the shortest distance between two consecutive stored
     * settlements. With neither, a gap inside the period makes the result incomplete without named instants.
     *
     * <p>Rule: BR-11, BR-37; A-05; D-97 rule 3 and its note of 2026-10-24; TECHNICAL_DESIGN 7.8.
     * <p>Reference: Binance FAQ, <i>Introduction to Binance Futures Funding Rates</i> (only positions open at a
     * funding timestamp pay or receive it).
     *
     * @param direction LONG or SHORT
     * @param quantity the position size, above 0
     * @param entryTime when the position was filled
     * @param exitTime when it closed, or the present for an open position
     * @param history the pair's settlements, announcements and snapshot gaps covering the period
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
        SortedSet<Instant> missing = new TreeSet<>();
        history.scheduledTimes().stream()
                .filter(time -> inPeriod(time, entryTime, exitTime) && !settled.contains(time))
                .forEach(missing::add);
        List<SnapshotGap> gaps = history.snapshotGaps().stream()
                .filter(gap -> gap.to().isAfter(entryTime) && gap.from().isBefore(exitTime))
                .toList();
        boolean gapWithoutInterval = false;
        if (!gaps.isEmpty()) {
            Optional<Duration> interval = fundingInterval(history);
            if (interval.isEmpty()) {
                gapWithoutInterval = true;
            } else {
                List<Instant> known = knownFundingTimes(history);
                for (SnapshotGap gap : gaps) {
                    if (gap.length().compareTo(interval.get()) > 0) {
                        fundingTimesIn(gap, interval.get(), known, entryTime, exitTime).stream()
                                .filter(time -> !settled.contains(time))
                                .forEach(missing::add);
                    }
                }
            }
        }
        BigDecimal amount = Rounding.toAmount(sum);
        return missing.isEmpty() && !gapWithoutInterval
                ? FundingFee.complete(amount)
                : FundingFee.partial(amount, List.copyOf(missing));
    }

    /**
     * The funding interval of the pair as the history shows it, never assumed (BR-11): the shortest distance between
     * two consecutive announced instants whose snapshots have no gap between them, so the second announcement
     * directly followed the first; otherwise the shortest distance between two consecutive stored settlements;
     * otherwise none.
     *
     * <p>Rule: BR-11; A-05.
     */
    static Optional<Duration> fundingInterval(FundingHistory history) {
        List<FundingAnnouncement> announcements = history.announcements().stream()
                .sorted(Comparator.comparing(FundingAnnouncement::fundingTime))
                .toList();
        Optional<Duration> announced = Optional.empty();
        for (int i = 1; i < announcements.size(); i++) {
            FundingAnnouncement first = announcements.get(i - 1);
            FundingAnnouncement second = announcements.get(i);
            boolean followedDirectly = history.snapshotGaps().stream()
                    .noneMatch(gap ->
                            !gap.from().isBefore(first.lastSeen()) && !gap.to().isAfter(second.firstSeen()));
            if (followedDirectly) {
                announced = shorter(announced, Duration.between(first.fundingTime(), second.fundingTime()));
            }
        }
        if (announced.isPresent()) {
            return announced;
        }
        List<Instant> stored = Stream.concat(
                        history.earlierSettlementTimes().stream(),
                        history.settlements().stream().map(SettledFunding::fundingTime))
                .distinct()
                .sorted()
                .toList();
        Optional<Duration> fromStored = Optional.empty();
        for (int i = 1; i < stored.size(); i++) {
            fromStored = shorter(fromStored, Duration.between(stored.get(i - 1), stored.get(i)));
        }
        return fromStored;
    }

    private static Optional<Duration> shorter(Optional<Duration> current, Duration candidate) {
        boolean shorter = current.map(known -> candidate.compareTo(known) < 0).orElse(true);
        return candidate.isPositive() && shorter ? Optional.of(candidate) : current;
    }

    /** Every funding instant the history knows, announced or stored, oldest first. */
    private static List<Instant> knownFundingTimes(FundingHistory history) {
        return Stream.of(
                        history.announcements().stream().map(FundingAnnouncement::fundingTime),
                        history.settlements().stream().map(SettledFunding::fundingTime),
                        history.earlierSettlementTimes().stream())
                .flatMap(times -> times)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * The pair's funding instants in the part of the gap inside the period, {@code (max(from, entry), min(to, exit)]}:
     * the known instant nearest the start of the gap, stepped by the interval. Only called with an interval, which
     * comes from at least one known instant.
     */
    private static List<Instant> fundingTimesIn(
            SnapshotGap gap, Duration interval, List<Instant> known, Instant entryTime, Instant exitTime) {
        Instant low = gap.from().isAfter(entryTime) ? gap.from() : entryTime;
        Instant high = gap.to().isBefore(exitTime) ? gap.to() : exitTime;
        Instant anchor = known.stream()
                .min(Comparator.comparing(
                        time -> Duration.between(time, gap.from()).abs()))
                .orElseThrow();
        long step = interval.toMillis();
        long steps = Math.floorDiv(low.toEpochMilli() - anchor.toEpochMilli(), step) + 1;
        List<Instant> times = new ArrayList<>();
        for (Instant time = anchor.plusMillis(steps * step); !time.isAfter(high); time = time.plusMillis(step)) {
            times.add(time);
        }
        return times;
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
