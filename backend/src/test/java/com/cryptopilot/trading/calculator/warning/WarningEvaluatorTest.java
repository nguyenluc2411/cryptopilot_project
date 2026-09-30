package com.cryptopilot.trading.calculator.warning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.cryptopilot.trading.WarningSeverity;
import com.cryptopilot.trading.WarningType;
import com.cryptopilot.trading.model.PlanCalculation;
import com.cryptopilot.trading.model.PlanWarning;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * All warning rules applied together: a clean plan, a plan that raises several warnings at once, their order, and
 * the activation block of a BLOCKING warning.
 *
 * <p>Rule: BR-25, BR-26, BR-28, BR-29, BR-32; SRS 3.5.1 (MSG17, MSG18).
 */
class WarningEvaluatorTest {

    @Test
    void BR29_aPlanWithinEveryThreshold_raisesNothing() {
        List<PlanWarning> warnings =
                WarningEvaluator.evaluate(PlanFixture.futuresLong().build());

        assertThat(warnings).isEmpty();
        assertThat(WarningEvaluator.blocksActivation(warnings)).isFalse();
    }

    /**
     * Futures LONG E 100, S 89, T 105, risk 2 % on a BALANCED profile, 20x, funding 0.002, other open risk 30: the
     * stop is below the 20x liquidation price of 95.39, the ratio is 5 / 11, the risk and leverage exceed the profile,
     * the total open risk is 4.9998 % of 4 %, the stop is 11 % away and a LONG pays the rate. Only
     * INSUFFICIENT_CAPITAL stays silent (margin 9.09 of 1,000).
     */
    @Test
    void BR29_aPlanBreakingSevenRules_raisesSevenWarnings_mostSevereFirst() {
        List<PlanWarning> warnings = WarningEvaluator.evaluate(PlanFixture.futuresLong()
                .stop("89")
                .takeProfit("105")
                .riskPercent("2")
                .leverage(20)
                .fundingRate("0.002")
                .otherOpenRisk("30")
                .build());

        assertThat(warnings)
                .extracting(PlanWarning::type)
                .containsExactly(
                        WarningType.SL_BEYOND_LIQUIDATION,
                        WarningType.LOW_RR,
                        WarningType.OVERSIZED_POSITION,
                        WarningType.HIGH_LEVERAGE,
                        WarningType.TOTAL_OPEN_RISK,
                        WarningType.HIGH_FUNDING_RATE,
                        WarningType.WIDE_STOP_LOSS);
        assertThat(warnings).extracting(PlanWarning::severity).isSortedAccordingTo((a, b) -> b.compareTo(a));
        assertThat(WarningEvaluator.blocksActivation(warnings)).isTrue();
    }

    /** Spot, stop 0.99 below E 100 with risk 1 % of 1,000: notional 1,010.1 and a ratio of 2 / 0.99. */
    @Test
    void BR25_aSpotPlanAboveItsCapital_blocksActivation() {
        List<PlanWarning> warnings = WarningEvaluator.evaluate(
                PlanFixture.spot().stop("99.01").takeProfit("102").build());

        assertThat(warnings).extracting(PlanWarning::type).containsExactly(WarningType.INSUFFICIENT_CAPITAL);
        assertThat(WarningEvaluator.blocksActivation(warnings)).isTrue();
    }

    @Test
    void BR32_warningsWithoutBlocking_doNotBlockActivation() {
        List<PlanWarning> warnings = WarningEvaluator.evaluate(
                PlanFixture.futuresLong().leverage(6).takeProfit("106").build());

        assertThat(warnings)
                .extracting(PlanWarning::type)
                .containsExactly(WarningType.LOW_RR, WarningType.HIGH_LEVERAGE);
        assertThat(WarningEvaluator.blocksActivation(warnings)).isFalse();
    }

    @ParameterizedTest(name = "{0} is {1}")
    @CsvSource({
        "INSUFFICIENT_CAPITAL, BLOCKING",
        "SL_BEYOND_LIQUIDATION, BLOCKING",
        "LOW_RR, WARNING",
        "OVERSIZED_POSITION, WARNING",
        "HIGH_LEVERAGE, WARNING",
        "TOTAL_OPEN_RISK, WARNING",
        "WIDE_STOP_LOSS, INFO",
        "HIGH_FUNDING_RATE, WARNING"
    })
    void BR29_eachType_hasTheSeverityTheRuleGivesIt(WarningType type, WarningSeverity severity) {
        assertThat(type.severity()).isEqualTo(severity);
    }

    /** The names are stored in {@code trading_plan_warning}, whose CHECK constraints accept exactly these. */
    @Test
    void theTypesAndSeverities_areTheValuesTheWarningTableAccepts() throws IOException {
        assertThat(Arrays.stream(WarningType.values()).map(Enum::name))
                .containsExactlyInAnyOrderElementsOf(schemaValues("ck_trading_plan_warning_type"));
        assertThat(Arrays.stream(WarningSeverity.values()).map(Enum::name))
                .containsExactlyInAnyOrderElementsOf(schemaValues("ck_trading_plan_warning_severity"));
    }

    @Test
    void aPlanCalculation_matchesItsMarket() {
        PlanCalculation futures = PlanFixture.futuresLong().build();
        PlanCalculation spot = PlanFixture.spot().build();

        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        new PlanCalculation(spot.plan(), spot.sized(), futures.liquidation(), null, spot.thresholds()));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> new PlanCalculation(futures.plan(), futures.sized(), null, null, futures.thresholds()));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> new PlanCalculation(spot.plan(), spot.sized(), null, BigDecimal.ONE, spot.thresholds()));
    }

    private static List<String> schemaValues(String constraint) throws IOException {
        try (InputStream in = WarningEvaluatorTest.class.getResourceAsStream("/schema/expected-enums.csv");
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return reader.lines()
                    .map(line -> line.split(","))
                    .filter(cells -> cells.length == 4 && cells[2].equals(constraint))
                    .findFirst()
                    .map(cells -> List.of(cells[3].split("\\|")))
                    .orElseThrow();
        }
    }
}
