package com.cryptopilot.trading.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Slippage moves a taker fill against the Trader and rounds it to the tick against the Trader; a marketable LIMIT is
 * never filled past its limit.
 *
 * <p>Rule: BR-36; D-111 E5; D-112 R6 and its note; TECHNICAL_DESIGN 7.8.
 */
class SlippageCalculatorTest {

    private static final BigDecimal TICK = new BigDecimal("0.01");

    /**
     * TD 7.8: LONG MARKET at 100.00 with 10 bps buys at 100.10; SHORT STOP at 95.00 sells at 94.905, rounded down to
     * 94.90. A buy rounds up: 100.01 × 1.001 = 100.11001 → 100.12; a sell down: 100.01 × 0.999 = 99.90999 → 99.90.
     */
    @ParameterizedTest(name = "{1} at {0} with {2} bps -> {3}")
    @CsvSource({
        "100.00, true,  10, 100.10",
        "95.00,  false, 10, 94.90",
        "100.01, true,  10, 100.12",
        "100.01, false, 10, 99.90",
        "100.00, true,  0,  100.00",
        "95.00,  false, 0,  95.00"
    })
    void BR36_aTakerFill_slipsAgainstTheTrader_andRoundsAgainstThemToTheTick(
            String reference, boolean buy, int bps, String fill) {
        assertThat(SlippageCalculator.takerFill(new BigDecimal(reference), buy, bps, TICK))
                .isEqualByComparingTo(fill);
    }

    /** Zero basis points leave the reference exactly as it was, scale included, so no earlier result moves. */
    @Test
    void BR36_noSlippage_returnsTheReferenceUntouched() {
        BigDecimal reference = new BigDecimal("99.5");

        assertThat(SlippageCalculator.takerFill(reference, true, 0, TICK)).isSameAs(reference);
    }

    @Test
    void BR36_aNegativeSlippage_isRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SlippageCalculator.takerFill(BigDecimal.TEN, true, -1, TICK));
    }

    /**
     * D-112 note (team lead, 2026-10-24): a LIMIT the last price already reaches fills at the last price with the
     * slippage, but never worse than its limit. LONG LIMIT 100: last 99.95 slips to 100.05, capped at 100; last 99.00
     * slips to 99.099 → 99.10, below the limit. SHORT LIMIT 100: last 100.05 slips to 99.94995 → 99.94, capped at 100;
     * last 101 slips to 100.899 → 100.89.
     */
    @ParameterizedTest(name = "{0} LIMIT 100, last {1} -> {2}")
    @CsvSource({
        "LONG,  99.95,  100.00",
        "LONG,  99.00,  99.10",
        "LONG,  100.00, 100.00",
        "SHORT, 100.05, 100.00",
        "SHORT, 101.00, 100.89",
        "SHORT, 100.00, 100.00"
    })
    void BR36_aMarketableLimit_slips_butNeverPastItsLimit(Direction direction, String last, String fill) {
        assertThat(SlippageCalculator.marketableLimitFill(
                        new BigDecimal(last), new BigDecimal("100.00"), direction, 10, TICK))
                .isEqualByComparingTo(fill);
    }

    @Test
    void BR36_aLongBuysIn_andAShortSellsIn() {
        assertThat(SlippageCalculator.buysOnEntry(Direction.LONG)).isTrue();
        assertThat(SlippageCalculator.buysOnEntry(Direction.SHORT)).isFalse();
    }
}
