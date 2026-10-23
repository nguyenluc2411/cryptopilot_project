package com.cryptopilot.trading.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.cryptopilot.market.FundingHistory;
import com.cryptopilot.market.SettledFunding;
import com.cryptopilot.trading.model.FundingFee;
import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The funding of a Futures position from its pair's stored settlements: sign by side, the period boundaries, several
 * settlements, a settlement not stored yet, and rounding once at the end.
 *
 * <p>Rule: BR-37; A-05; D-97 rule 3; TECHNICAL_DESIGN 7.8.
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
        FundingHistory history = new FundingHistory(
                List.of(settled(T0, "90", "0.01"), settled(T8, "101", "0.0001"), settled(T24, "90", "0.01")),
                List.of(T0, T8, T24));

        FundingFee fee = FundingCalculator.fundingFee(Direction.LONG, QTY, T0, T16, history);

        assertThat(fee.isComplete()).isTrue();
        assertThat(fee.amount()).isEqualByComparingTo("-0.0202");
    }

    // ------------------------------------------------------------------ completeness (A-05)

    /** An announced settlement not stored yet: no amount at all, not the sum of the others; the instant is named. */
    @Test
    void A05_aMissingSettlement_makesTheFundingIncomplete_withoutAnAmount() {
        FundingHistory history = new FundingHistory(
                List.of(settled(T8, "101", "0.0001"), settled(T24, "100", "0.0001")), List.of(T8, T16, T24));

        FundingFee fee = FundingCalculator.fundingFee(Direction.LONG, QTY, T0, T24, history);

        assertThat(fee.isComplete()).isFalse();
        assertThat(fee.amount()).isNull();
        assertThat(fee.missingFundingTimes()).containsExactly(T16);
    }

    /** The same period once the late row is stored: the full sum, and the same sum on every later call. */
    @Test
    void A05_onceTheLateSettlementIsStored_theRecomputeIsCompleteAndStable() {
        List<Instant> announced = List.of(T8, T16);
        FundingHistory before = new FundingHistory(List.of(settled(T8, "101", "0.0001")), announced);
        FundingHistory after =
                new FundingHistory(List.of(settled(T8, "101", "0.0001"), settled(T16, "99", "-0.0002")), announced);

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
    void A05_aFundingFee_hasAnAmountExactlyWhenNothingIsMissing() {
        assertThatIllegalArgumentException().isThrownBy(() -> new FundingFee(BigDecimal.ONE, List.of(T8)));
        assertThatIllegalArgumentException().isThrownBy(() -> new FundingFee(null, List.of()));
    }

    /** Every settlement stored is also announced, so the history is complete. */
    private static FundingHistory history(SettledFunding... settlements) {
        return new FundingHistory(
                List.of(settlements),
                List.of(settlements).stream().map(SettledFunding::fundingTime).toList());
    }

    private static SettledFunding settled(Instant time, String mark, String rate) {
        return new SettledFunding(time, new BigDecimal(rate), new BigDecimal(mark));
    }
}
