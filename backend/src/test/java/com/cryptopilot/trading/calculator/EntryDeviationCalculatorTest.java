package com.cryptopilot.trading.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.cryptopilot.trading.model.EntryDeviation;
import com.cryptopilot.trading.model.EntryDeviation.Verdict;
import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The entry deviation in R and the decision on a MARKET entry at activation, on the BTC example of TECHNICAL_DESIGN
 * 7.8: LONG planned 100,000, SL 98,000, TP 106,000, so 1R is 2,000; the SHORT mirror is planned 100,000, SL 102,000,
 * TP 94,000. Confirm 0.25, block 0.75.
 *
 * <p>Rule: BR-69; D-111 (M2), D-112 (R7).
 */
class EntryDeviationCalculatorTest {

    private static final BigDecimal CONFIRM = new BigDecimal("0.25");
    private static final BigDecimal BLOCK = new BigDecimal("0.75");

    /** TD 7.8: the BTC LONG and SHORT examples, and the other fills of the worked example. */
    @ParameterizedTest(name = "{0} planned {1}, fill {2}, SL {3} -> {4}R")
    @CsvSource({
        "LONG,  100000, 100000, 98000,  0",
        "LONG,  100000, 101000, 98000,  0.5",
        "LONG,  100000, 102000, 98000,  1.0",
        "LONG,  100000, 99000,  98000,  -0.5",
        "SHORT, 50.00,  49.50,  52.00,  0.25",
        "SHORT, 50.00,  50.40,  52.00,  -0.2",
        "LONG,  100,    99.50,  95,     -0.1",
        "LONG,  100,    101,    95,     0.2"
    })
    void BR69_theDeviation_isTheSignedMoveOverThePlannedStopDistance(
            Direction direction, String planned, String fill, String stopLoss, String expected) {
        assertThat(EntryDeviationCalculator.deviationR(
                        direction, new BigDecimal(planned), new BigDecimal(fill), new BigDecimal(stopLoss)))
                .isEqualByComparingTo(expected);
    }

    /** Recorded at the scale of the journal column, half-even: 1 over 3 of 1R. */
    @Test
    void BR69_theDeviation_isRoundedToEightDecimals() {
        BigDecimal deviation = EntryDeviationCalculator.deviationR(
                Direction.LONG, new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("97"));

        assertThat(deviation).isEqualTo(new BigDecimal("0.33333333"));
    }

    @Test
    void BR69_aStopLossAtThePlannedEntry_isRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> EntryDeviationCalculator.deviationR(
                        Direction.LONG, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN));
    }

    /**
     * The bands, LONG and SHORT: below 0.25 proceeds, 0.25 up to and including 0.75 needs confirmation, above 0.75 is
     * refused; a better price proceeds. "Just" is one cent of BTC, 0.000005R.
     */
    @ParameterizedTest(name = "{0} fill {1} -> {2}")
    @CsvSource({
        "LONG,  100000,    PROCEED",
        "LONG,  100499.99, PROCEED",
        "LONG,  100500,    CONFIRM",
        "LONG,  101000,    CONFIRM",
        "LONG,  101500,    CONFIRM",
        "LONG,  101500.01, REFUSE",
        "LONG,  102000,    REFUSE",
        "LONG,  99000,     PROCEED",
        "SHORT, 100000,    PROCEED",
        "SHORT, 99500.01,  PROCEED",
        "SHORT, 99500,     CONFIRM",
        "SHORT, 99000,     CONFIRM",
        "SHORT, 98500,     CONFIRM",
        "SHORT, 98499.99,  REFUSE",
        "SHORT, 98000,     REFUSE",
        "SHORT, 101000,    PROCEED"
    })
    void BR69_aMarketEntry_proceedsNeedsConfirmationOrIsRefused_byItsDeviation(
            Direction direction, String fill, Verdict expected) {
        EntryDeviation deviation = btc(direction, fill, null);

        assertThat(deviation.verdict()).isEqualTo(expected);
        assertThat(deviation.takeProfitReached()).isFalse();
        assertThat(deviation.limitR()).isEqualByComparingTo(BLOCK);
    }

    /** A fill at or past the take profit is refused whatever its deviation, also within a wide limit. */
    @ParameterizedTest(name = "{0} fill {1}")
    @CsvSource({"LONG, 106000", "LONG, 107000", "SHORT, 94000", "SHORT, 93000"})
    void BR69_aFillAtOrPastTheTakeProfit_isRefused(Direction direction, String fill) {
        EntryDeviation deviation = EntryDeviationCalculator.atMarketActivation(
                direction,
                new BigDecimal("100000"),
                new BigDecimal(fill),
                stopLoss(direction),
                takeProfit(direction),
                CONFIRM,
                new BigDecimal("10"),
                null);

        assertThat(deviation.verdict()).isEqualTo(Verdict.REFUSE);
        assertThat(deviation.takeProfitReached()).isTrue();
    }

    /** A plan's own limit replaces the block value only when stricter: 0.5 refuses 0.6R, 0.9 is capped at 0.75. */
    @Test
    void BR69_thePlansOwnLimit_replacesTheBlockValue_onlyWhenStricter() {
        EntryDeviation strict = btc(Direction.LONG, "101200", new BigDecimal("0.5"));
        assertThat(strict.deviationR()).isEqualByComparingTo("0.6");
        assertThat(strict.limitR()).isEqualByComparingTo("0.5");
        assertThat(strict.verdict()).isEqualTo(Verdict.REFUSE);

        EntryDeviation atItsLimit = btc(Direction.LONG, "101000", new BigDecimal("0.5"));
        assertThat(atItsLimit.verdict()).isEqualTo(Verdict.CONFIRM);

        EntryDeviation wider = btc(Direction.LONG, "101600", new BigDecimal("0.9"));
        assertThat(wider.limitR()).isEqualByComparingTo(BLOCK);
        assertThat(wider.verdict()).isEqualTo(Verdict.REFUSE);
    }

    /** D-112 R7: a limit below the confirm value leaves no band to confirm: 0.1R proceeds, 0.2R is refused. */
    @Test
    void BR69_aPlanLimitBelowTheConfirmValue_leavesNoConfirmationBand() {
        BigDecimal limit = new BigDecimal("0.15");

        assertThat(btc(Direction.LONG, "100200", limit).verdict()).isEqualTo(Verdict.PROCEED);
        assertThat(btc(Direction.LONG, "100300", limit).verdict()).isEqualTo(Verdict.PROCEED);
        assertThat(btc(Direction.LONG, "100400", limit).verdict()).isEqualTo(Verdict.REFUSE);
        assertThat(btc(Direction.LONG, "100500", limit).verdict()).isEqualTo(Verdict.REFUSE);
    }

    @Test
    void BR69_anEntryAtOrPastTheTakeProfit_canOnlyBeRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EntryDeviation(BigDecimal.ONE, BLOCK, true, Verdict.CONFIRM));
    }

    private static EntryDeviation btc(Direction direction, String fill, BigDecimal planLimit) {
        return EntryDeviationCalculator.atMarketActivation(
                direction,
                new BigDecimal("100000"),
                new BigDecimal(fill),
                stopLoss(direction),
                takeProfit(direction),
                CONFIRM,
                BLOCK,
                planLimit);
    }

    private static BigDecimal stopLoss(Direction direction) {
        return new BigDecimal(direction == Direction.LONG ? "98000" : "102000");
    }

    private static BigDecimal takeProfit(Direction direction) {
        return new BigDecimal(direction == Direction.LONG ? "106000" : "94000");
    }
}
