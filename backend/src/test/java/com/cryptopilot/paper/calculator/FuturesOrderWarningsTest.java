package com.cryptopilot.paper.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.cryptopilot.paper.model.FuturesWarning;
import com.cryptopilot.paper.model.enums.FuturesWarningType;
import com.cryptopilot.paper.model.enums.PositionDirection;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The rules of the Futures order form's warnings, each at its edge: equal to a threshold passes where the plan's rule
 * passes it.
 *
 * <p>Rule: TR-26; BR-28, BR-29.
 */
class FuturesOrderWarningsTest {

    @Test
    void TR26_aCalmOrder_raisesNothing() {
        assertThat(FuturesOrderWarnings.evaluate(input(PositionDirection.LONG, "95", "110", "0", null)))
                .isEmpty();
    }

    @Test
    void TR26_eachThreshold_isMetAtItsEdge() {
        // Stop 10 % away (not above 10), reward 15 to risk 10 (1.5, not below), loss 10 = 0.5 % of 2,000.
        assertThat(FuturesOrderWarnings.evaluate(input(PositionDirection.LONG, "90", "115", "0", "0.001")))
                .extracting(FuturesWarning::type)
                .containsExactly(FuturesWarningType.HIGH_FUNDING_RATE);
        // A short's stop above; the long side does not pay a negative rate, the short does.
        assertThat(FuturesOrderWarnings.evaluate(input(PositionDirection.SHORT, "110", "85", "0", "0.001")))
                .isEmpty();
        assertThat(FuturesOrderWarnings.evaluate(input(PositionDirection.SHORT, "110", "85", "0", "-0.001")))
                .extracting(FuturesWarning::type)
                .containsExactly(FuturesWarningType.HIGH_FUNDING_RATE);
    }

    @Test
    void TR26_aStopAtTheLiquidationPrice_isBeyondIt_onEitherSide() {
        assertThat(FuturesOrderWarnings.evaluate(input(PositionDirection.LONG, "95", null, "95", null)))
                .extracting(FuturesWarning::type)
                .containsExactly(FuturesWarningType.SL_BEYOND_LIQUIDATION);
        assertThat(FuturesOrderWarnings.evaluate(input(PositionDirection.SHORT, "105", null, "105", null)))
                .extracting(FuturesWarning::type)
                .containsExactly(FuturesWarningType.SL_BEYOND_LIQUIDATION);
        assertThat(FuturesOrderWarnings.evaluate(input(PositionDirection.SHORT, "105", null, "105.1", null)))
                .isEmpty();
    }

    @Test
    void TR26_everyWarning_mostSevereFirst() {
        FuturesOrderWarnings.Input in = new FuturesOrderWarnings.Input(
                PositionDirection.LONG,
                new BigDecimal("100"),
                BigDecimal.ONE,
                20,
                new BigDecimal("3000"),
                new BigDecimal("2000"),
                new BigDecimal("2000"),
                new BigDecimal("85"),
                new BigDecimal("80"),
                new BigDecimal("101"),
                new BigDecimal("0.002"),
                3,
                new BigDecimal("0.5"),
                new BigDecimal("1.5"),
                BigDecimal.TEN,
                new BigDecimal("0.001"));

        List<FuturesWarning> warnings = FuturesOrderWarnings.evaluate(in);

        assertThat(warnings)
                .extracting(FuturesWarning::type)
                .containsExactly(
                        FuturesWarningType.INSUFFICIENT_MARGIN,
                        FuturesWarningType.SL_BEYOND_LIQUIDATION,
                        FuturesWarningType.HIGH_LEVERAGE,
                        FuturesWarningType.OVERSIZED_POSITION,
                        FuturesWarningType.LOW_RR,
                        FuturesWarningType.HIGH_FUNDING_RATE,
                        FuturesWarningType.WIDE_STOP_LOSS);
        assertThat(warnings.getFirst().severity()).isEqualTo(FuturesWarningType.Severity.BLOCKING);
        assertThat(warnings.getFirst().message()).isEqualTo("the order costs 3000 USDT, more than the 2000 available");
    }

    @Test
    void TR26_anEmptyWallet_orAStopAtTheEntry_skipsTheRulesThatDivideByThem() {
        FuturesOrderWarnings.Input in = new FuturesOrderWarnings.Input(
                PositionDirection.LONG,
                new BigDecimal("100"),
                BigDecimal.ONE,
                1,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("100"),
                new BigDecimal("110"),
                null,
                3,
                new BigDecimal("0.5"),
                new BigDecimal("1.5"),
                BigDecimal.TEN,
                new BigDecimal("0.001"));

        assertThat(FuturesOrderWarnings.evaluate(in)).isEmpty();
    }

    @Test
    void TR26_aWarningCarriesItsTypesSeverity_only() {
        assertThat(new FuturesWarning(FuturesWarningType.WIDE_STOP_LOSS, "far").severity())
                .isEqualTo(FuturesWarningType.Severity.INFO);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new FuturesWarning(
                        FuturesWarningType.WIDE_STOP_LOSS, FuturesWarningType.Severity.BLOCKING, "far"));
    }

    /** One unit at 100, 1x, a wallet of 2,000, the CONSERVATIVE profile and the seeded thresholds. */
    private static FuturesOrderWarnings.Input input(
            PositionDirection direction, String stop, String takeProfit, String liquidation, String rate) {
        return new FuturesOrderWarnings.Input(
                direction,
                new BigDecimal("100"),
                BigDecimal.ONE,
                1,
                new BigDecimal("100"),
                new BigDecimal("2000"),
                new BigDecimal("2000"),
                new BigDecimal(liquidation),
                stop == null ? null : new BigDecimal(stop),
                takeProfit == null ? null : new BigDecimal(takeProfit),
                rate == null ? null : new BigDecimal(rate),
                3,
                new BigDecimal("0.5"),
                new BigDecimal("1.5"),
                BigDecimal.TEN,
                new BigDecimal("0.001"));
    }
}
