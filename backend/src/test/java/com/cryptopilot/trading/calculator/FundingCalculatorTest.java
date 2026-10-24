package com.cryptopilot.trading.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The funding of a Futures position from its pair's stored settlements: sign by side, the period boundaries, several
 * settlements, a settlement not stored yet, a settlement hidden by a gap in the snapshots, and rounding once at the
 * end.
 *
 * <p>Rule: BR-11, BR-37; A-05; D-97 rule 3 and its note of 2026-10-24; TECHNICAL_DESIGN 7.8.
 */
class FundingCalculatorTest {

    private static final Instant T0 = Instant.parse("2026-10-20T00:00:00Z");
    private static final Instant T8 = T0.plus(Duration.ofHours(8));
    private static final Instant T16 = T0.plus(Duration.ofHours(16));
    private static final Instant T24 = T0.plus(Duration.ofHours(24));
    private static final BigDecimal QTY = new BigDecimal("2");

    // ------------------------------------------------------------------ sign

    /** TECHNICAL_DESIGN 7.8 vector: mark 101, rate 0.0001, qty 2; the LONG pays 0.0202, the SHORT receives it. */
    @ParameterizedTest
    @CsvSource({"LONG, -0.0202", "SHORT, 0.0202"})
    void BR37_aPositiveRate_makesTheLongPayAndTheShortReceive(Direction side, String expected) {
        FundingFee fee = FundingCalculator.fundingFee(
                side, QTY, T0.minusSeconds(60), T8.plusSeconds(60), history(settled(T8, "101", "0.0001")));

        assertThat(fee.isComplete()).isTrue();
        assertThat(fee.amount()).isEqualByComparingTo(expected);
        assertThat(fee.amount().scale()).isEqualTo(8);
    }

    @ParameterizedTest
    @CsvSource({"LONG, 0.0404", "SHORT, -0.0404"})
    void BR37_aNegativeRate_makesTheLongReceiveAndTheShortPay(Direction side, String expected) {
        FundingFee fee = FundingCalculator.fundingFee(
                side, QTY, T0.minusSeconds(60), T8.plusSeconds(60), history(settled(T8, "101", "-0.0002")));

        assertThat(fee.amount()).isEqualByComparingTo(expected);
    }

    /**
     * Three settlements, rates of both signs: LONG −2 × 101 × 0.0001 + 2 × 99 × 0.0002 − 2 × 100.5 × 0.00015
     * = −0.0202 + 0.0396 − 0.03015 = −0.01075; the SHORT gets the opposite.
     */
    @ParameterizedTest
    @CsvSource({"LONG, -0.01075", "SHORT, 0.01075"})
    void BR37_severalSettlements_areSummedWithTheirOwnSign(Direction side, String expected) {
        FundingHistory history =
                history(settled(T8, "101", "0.0001"), settled(T16, "99", "-0.0002"), settled(T24, "100.5", "0.00015"));

        FundingFee fee = FundingCalculator.fundingFee(side, QTY, T0.plusSeconds(1), T24, history);

        assertThat(fee.amount()).isEqualByComparingTo(expected);
    }

    @Test
    void BR37_noSettlementWhileOpen_isACompleteZero() {
        FundingFee fee = FundingCalculator.fundingFee(
                Direction.LONG, QTY, T0.plusSeconds(60), T8.minusSeconds(60), history(settled(T8, "101", "0.0001")));

        assertThat(fee.isComplete()).isTrue();
        assertThat(fee.amount()).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------ boundaries: entry < t <= exit

    @Test
    void BR37_aPositionOpenedExactlyAtAFundingTime_doesNotPayIt() {
        FundingFee fee = FundingCalculator.fundingFee(
                Direction.LONG, QTY, T8, T16.minusSeconds(60), history(settled(T8, "101", "0.0001")));

        assertThat(fee.amount()).isEqualByComparingTo("0");
    }

    @Test
    void BR37_aPositionClosedExactlyAtAFundingTime_paysIt() {
        FundingFee fee = FundingCalculator.fundingFee(
                Direction.LONG, QTY, T0.plusSeconds(60), T8, history(settled(T8, "101", "0.0001")));

        assertThat(fee.amount()).isEqualByComparingTo("-0.0202");
    }

    @Test
    void BR37_aPositionOpenedAndClosedAtTheSameFundingTime_paysNothing() {
        FundingFee fee =
                FundingCalculator.fundingFee(Direction.LONG, QTY, T8, T8, history(settled(T8, "101", "0.0001")));

        assertThat(fee.amount()).isEqualByComparingTo("0");
    }

    @Test
    void BR37_settlementsAndAnnouncementsOutsideThePeriod_areIgnored() {
        FundingHistory history = announced(
                List.of(settled(T0, "90", "0.01"), settled(T8, "101", "0.0001"), settled(T24, "90", "0.01")),
                List.of(T0, T8, T24));

        FundingFee fee = FundingCalculator.fundingFee(Direction.LONG, QTY, T0, T16, history);

        assertThat(fee.isComplete()).isTrue();
        assertThat(fee.amount()).isEqualByComparingTo("-0.0202");
    }

    // ------------------------------------------------------------------ completeness (A-05)

    /**
     * An announced settlement not stored yet: the funding is incomplete, carries the sum of the two stored ones
     * (LONG −2 × 101 × 0.0001 − 2 × 100 × 0.0001 = −0.0402) and names the missing instant (D-97 note of 2026-10-24).
     */
    @Test
    void A05_aMissingSettlement_makesTheFundingIncomplete_withTheKnownSum() {
        FundingHistory history =
                announced(List.of(settled(T8, "101", "0.0001"), settled(T24, "100", "0.0001")), List.of(T8, T16, T24));

        FundingFee fee = FundingCalculator.fundingFee(Direction.LONG, QTY, T0, T24, history);

        assertThat(fee.isComplete()).isFalse();
        assertThat(fee.amount()).isEqualByComparingTo("-0.0402");
        assertThat(fee.missingFundingTimes()).containsExactly(T16);
    }

    /** The same period once the late row is stored: the full sum, and the same sum on every later call. */
    @Test
    void A05_onceTheLateSettlementIsStored_theRecomputeIsCompleteAndStable() {
        List<Instant> announced = List.of(T8, T16);
        FundingHistory before = announced(List.of(settled(T8, "101", "0.0001")), announced);
        FundingHistory after =
                announced(List.of(settled(T8, "101", "0.0001"), settled(T16, "99", "-0.0002")), announced);

        assertThat(FundingCalculator.fundingFee(Direction.SHORT, QTY, T0, T16, before)
                        .isComplete())
                .isFalse();
        FundingFee recomputed = FundingCalculator.fundingFee(Direction.SHORT, QTY, T0, T16, after);
        // SHORT: +0.0202 − 0.0396
        assertThat(recomputed.amount()).isEqualByComparingTo("-0.0194");
        assertThat(FundingCalculator.fundingFee(Direction.SHORT, QTY, T0, T16, after))
                .isEqualTo(recomputed);
    }

    @Test
    void A05_aSettlementStoredTwice_isCountedOnce() {
        FundingHistory history = history(settled(T8, "101", "0.0001"), settled(T8, "101", "0.0001"));

        assertThat(FundingCalculator.fundingFee(Direction.LONG, QTY, T0, T16, history)
                        .amount())
                .isEqualByComparingTo("-0.0202");
    }

    // ------------------------------------------------------------------ precision

    /**
     * Each settlement moves 0.000000004, below the stored scale; kept at full precision the three sum to
     * 0.000000012 and round once to 0.00000001. Rounding each first would give 0.
     */
    @Test
    void ADR008_settlementsAreSummedAtFullPrecision_andRoundedOnce() {
        FundingHistory history = history(
                settled(T8, "1", "0.000000004"), settled(T16, "1", "0.000000004"), settled(T24, "1", "0.000000004"));

        FundingFee fee = FundingCalculator.fundingFee(Direction.SHORT, BigDecimal.ONE, T0, T24, history);

        assertThat(fee.amount()).isEqualByComparingTo("0.00000001");
    }

    @Test
    void BR37_anExitBeforeTheEntry_orNoQuantity_isRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FundingCalculator.fundingFee(Direction.LONG, QTY, T8, T0, history()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FundingCalculator.fundingFee(Direction.LONG, BigDecimal.ZERO, T0, T8, history()));
    }

    @Test
    void A05_aFundingFee_alwaysHasAnAmount_andNamesMissingSettlementsOnlyWhenIncomplete() {
        assertThatIllegalArgumentException().isThrownBy(() -> new FundingFee(BigDecimal.ONE, false, List.of(T8)));
        assertThatNullPointerException().isThrownBy(() -> FundingFee.partial(null, List.of(T8)));
        assertThat(FundingFee.partial(BigDecimal.ONE, List.of()).isComplete()).isFalse();
    }

    // ------------------------------------------------------------------ safety net: snapshot gaps (A-05)

    /**
     * The review scenario: 8h funding, a LONG open 00:00–20:00, the snapshots down from 07:00 to 17:00. The last
     * snapshot before the gap announces 08:00, the first after it announces 00:00 of the next day, so no snapshot ever
     * announced 16:00. 08:00 is stored, 16:00 is not: incomplete, 16:00 named, the known sum is the 08:00 one.
     */
    @Test
    void A05_aGapLongerThanTheInterval_hidingAnUnstoredSettlement_isIncomplete_andNamesIt() {
        FundingHistory history = observed(
                Duration.ofHours(8),
                T0,
                T0.plus(Duration.ofHours(20)),
                outage(at(7), at(17)),
                stored(T0.minus(Duration.ofHours(16)), T0.minus(Duration.ofHours(8)), T0),
                settled(T8, "101", "0.0001"));

        FundingFee fee = FundingCalculator.fundingFee(Direction.LONG, QTY, T0, T0.plus(Duration.ofHours(20)), history);

        assertThat(history.scheduledTimes()).as("no snapshot announced 16:00").containsExactly(T8);
        assertThat(fee.isComplete()).isFalse();
        assertThat(fee.missingFundingTimes()).containsExactly(T16);
        assertThat(fee.amount()).isEqualByComparingTo("-0.0202");
    }

    /** The same outage once both settlements of the gap are stored: complete, with both summed. */
    @Test
    void A05_theSameGapWithBothSettlementsStored_isComplete() {
        FundingHistory history = observed(
                Duration.ofHours(8),
                T0,
                T0.plus(Duration.ofHours(20)),
                outage(at(7), at(17)),
                stored(T0.minus(Duration.ofHours(16)), T0.minus(Duration.ofHours(8)), T0),
                settled(T8, "101", "0.0001"),
                settled(T16, "99", "-0.0002"));

        FundingFee fee = FundingCalculator.fundingFee(Direction.LONG, QTY, T0, T0.plus(Duration.ofHours(20)), history);

        assertThat(fee.isComplete()).isTrue();
        // LONG: −0.0202 + 0.0396
        assertThat(fee.amount()).isEqualByComparingTo("0.0194");
    }

    /**
     * A gap of five hours on an 8h symbol hides nothing: the snapshot before it announced 08:00, the next settlement
     * after it is announced again. Complete, though a step of less than 8h would have expected 12:00.
     */
    @Test
    void A05_aGapShorterThanTheInterval_isComplete() {
        FundingHistory history = observed(
                Duration.ofHours(8),
                T0,
                T0.plus(Duration.ofHours(20)),
                outage(at(7), at(12)),
                stored(T0.minus(Duration.ofHours(16)), T0.minus(Duration.ofHours(8)), T0),
                settled(T8, "101", "0.0001"),
                settled(T16, "99", "-0.0002"));

        FundingFee fee = FundingCalculator.fundingFee(Direction.LONG, QTY, T0, T0.plus(Duration.ofHours(20)), history);

        assertThat(fee.isComplete()).isTrue();
        assertThat(fee.missingFundingTimes()).isEmpty();
    }

    /**
     * A 4h symbol with the snapshots down 07:00–13:00: six hours is longer than its interval, so 12:00 is expected
     * and is missing. An 8h interval taken as a constant would find the gap too short and call it complete.
     */
    @Test
    void A05_aFourHourSymbol_derivesItsInterval_andFindsTheSettlementTheGapHid() {
        Instant t4 = at(4);
        Instant t12 = at(12);
        FundingHistory history = observed(
                Duration.ofHours(4),
                T0,
                T0.plus(Duration.ofHours(20)),
                outage(at(7), at(13)),
                stored(T0.minus(Duration.ofHours(8)), T0.minus(Duration.ofHours(4)), T0),
                settled(t4, "100", "0.0001"),
                settled(T8, "101", "0.0001"),
                settled(T16, "99", "0.0001"),
                settled(at(20), "99", "0.0001"));

        assertThat(FundingCalculator.fundingInterval(history)).contains(Duration.ofHours(4));
        FundingFee fee = FundingCalculator.fundingFee(Direction.LONG, QTY, T0, T0.plus(Duration.ofHours(20)), history);

        assertThat(fee.isComplete()).isFalse();
        assertThat(fee.missingFundingTimes()).containsExactly(t12);
    }

    /**
     * Two announcements on either side of a gap are not two consecutive settlements: 08:00 and 00:00 of the next day
     * are 16h apart across the outage. With no snapshot before the entry, the interval comes from the stored rows.
     */
    @Test
    void A05_withoutAnIntervalInTheSnapshots_theStoredSettlementsGiveIt() {
        FundingHistory history = observed(
                Duration.ofHours(8),
                T0,
                T0.plus(Duration.ofHours(20)),
                List.of(new Instant[] {T0, at(7).minusSeconds(60)}, new Instant[] {at(17), at(48)}),
                stored(T0.minus(Duration.ofHours(16)), T0.minus(Duration.ofHours(8)), T0),
                settled(T8, "101", "0.0001"));

        assertThat(FundingCalculator.fundingInterval(history)).contains(Duration.ofHours(8));
        FundingFee fee = FundingCalculator.fundingFee(Direction.LONG, QTY, T0, T0.plus(Duration.ofHours(20)), history);

        assertThat(fee.missingFundingTimes()).containsExactly(T16);
    }

    /** No interval in the snapshots and a single stored settlement: a gap in the period cannot be checked. */
    @Test
    void A05_aGapWithNoIntervalAtAll_isIncomplete_withoutNamedTimes() {
        FundingHistory history = observed(
                Duration.ofHours(8),
                T0,
                T0.plus(Duration.ofHours(20)),
                List.of(new Instant[] {T0, at(7).minusSeconds(60)}, new Instant[] {at(17), at(48)}),
                List.of(),
                settled(T8, "101", "0.0001"));

        assertThat(FundingCalculator.fundingInterval(history)).isEmpty();
        FundingFee fee = FundingCalculator.fundingFee(Direction.LONG, QTY, T0, T0.plus(Duration.ofHours(20)), history);

        assertThat(fee.isComplete()).isFalse();
        assertThat(fee.missingFundingTimes()).isEmpty();
        assertThat(fee.amount()).isEqualByComparingTo("-0.0202");
    }

    /**
     * Snapshots without a gap in the period announce every settlement in it, so no interval is needed: one
     * announcement and no stored row give no interval, and the result is still complete.
     */
    @Test
    void A05_noGapInThePeriod_needsNoInterval() {
        FundingHistory history = observed(
                Duration.ofHours(8),
                T0,
                T0.plus(Duration.ofHours(6)),
                List.<Instant[]>of(new Instant[] {T0, at(6)}),
                List.of());

        assertThat(FundingCalculator.fundingInterval(history)).isEmpty();

        FundingFee fee = FundingCalculator.fundingFee(Direction.LONG, QTY, T0, T0.plus(Duration.ofHours(6)), history);

        assertThat(fee.isComplete()).isTrue();
        assertThat(fee.amount()).isEqualByComparingTo("0");
    }

    /** A gap outside the period, however long, does not make it incomplete. */
    @Test
    void A05_aGapBeforeTheEntry_isIgnored() {
        FundingHistory history = observed(
                Duration.ofHours(8),
                T16,
                T16.plus(Duration.ofHours(4)),
                outage(at(1), at(14)),
                stored(T0),
                settled(T8, "101", "0.0001"));

        FundingFee fee = FundingCalculator.fundingFee(
                Direction.LONG, QTY, T16.plusSeconds(60), T16.plus(Duration.ofHours(4)), history);

        assertThat(fee.isComplete()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    /** Every settlement stored is also announced and the snapshots have no gap, so the history is complete. */
    private static FundingHistory history(SettledFunding... settlements) {
        return announced(
                List.of(settlements),
                List.of(settlements).stream().map(SettledFunding::fundingTime).toList());
    }

    /** Stored settlements and announced instants with snapshots that have no gap. */
    private static FundingHistory announced(List<SettledFunding> settlements, List<Instant> scheduled) {
        return new FundingHistory(settlements, scheduled, List.of(), List.of(), List.of());
    }

    /**
     * The history the repository would read for {@code (after, upTo]}: one snapshot per minute over the covered
     * stretches, each announcing the next settlement of a symbol with this interval from 00:00; announcements and gaps
     * over the look-back day and the period, as the database computes them.
     */
    private static FundingHistory observed(
            Duration interval,
            Instant after,
            Instant upTo,
            List<Instant[]> covered,
            List<Instant> earlierSettlements,
            SettledFunding... settlements) {
        Instant windowStart = after.minus(Duration.ofDays(1));
        TreeSet<Instant> snapshots = new TreeSet<>();
        for (Instant[] stretch : covered) {
            for (Instant t = stretch[0]; !t.isAfter(stretch[1]); t = t.plusSeconds(60)) {
                if (!t.isBefore(windowStart) && !t.isAfter(upTo)) {
                    snapshots.add(t);
                }
            }
        }
        Map<Instant, Instant[]> seen = new TreeMap<>();
        for (Instant snapshot : snapshots) {
            Instant next = nextFunding(snapshot, interval);
            seen.merge(next, new Instant[] {snapshot, snapshot}, (a, b) -> new Instant[] {a[0], b[1]});
        }
        List<FundingAnnouncement> announcements = seen.entrySet().stream()
                .map(e -> new FundingAnnouncement(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
        List<Instant> series = new ArrayList<>(snapshots);
        series.add(windowStart);
        series.add(upTo);
        Collections.sort(series);
        List<SnapshotGap> gaps = new ArrayList<>();
        for (int i = 1; i < series.size(); i++) {
            if (Duration.between(series.get(i - 1), series.get(i)).compareTo(Duration.ofMinutes(1)) > 0) {
                gaps.add(new SnapshotGap(series.get(i - 1), series.get(i)));
            }
        }
        List<Instant> scheduled = announcements.stream()
                .map(FundingAnnouncement::fundingTime)
                .filter(t -> t.isAfter(after) && !t.isAfter(upTo))
                .toList();
        return new FundingHistory(List.of(settlements), scheduled, announcements, gaps, earlierSettlements);
    }

    /** The first settlement strictly after the snapshot, on a grid of this interval from 00:00 UTC. */
    private static Instant nextFunding(Instant snapshot, Duration interval) {
        long step = interval.toMillis();
        return Instant.ofEpochMilli(Math.floorDiv(snapshot.toEpochMilli(), step) * step + step);
    }

    /** Snapshots over the look-back day and the period except while ingestion was down in {@code [from, to)}. */
    private static List<Instant[]> outage(Instant from, Instant to) {
        return List.of(
                new Instant[] {T0.minus(Duration.ofDays(2)), from.minusSeconds(60)},
                new Instant[] {to, T0.plus(Duration.ofDays(2))});
    }

    private static List<Instant> stored(Instant... times) {
        return List.of(times);
    }

    private static Instant at(int hour) {
        return T0.plus(Duration.ofHours(hour));
    }

    private static SettledFunding settled(Instant time, String mark, String rate) {
        return new SettledFunding(time, new BigDecimal(rate), new BigDecimal(mark));
    }
}
