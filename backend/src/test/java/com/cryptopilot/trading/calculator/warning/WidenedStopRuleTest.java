package com.cryptopilot.trading.calculator.warning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.cryptopilot.trading.model.PlanWarning;
import com.cryptopilot.trading.model.RiskProfileLimits;
import com.cryptopilot.trading.model.enums.WarningSeverity;
import com.cryptopilot.trading.model.enums.WarningType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * TP-E: a stop loss moved away from the entry warns, never blocks, when the position's risk or the total open risk
 * goes above the profile's limits. Capital 1,000, risk per trade 1 %, total open risk at most 4 %.
 *
 * <p>Rule: BR-29, BR-66; D-124; Q-79.
 */
class WidenedStopRuleTest {

    private static final BigDecimal CAPITAL = new BigDecimal("1000");
    private static final RiskProfileLimits LIMITS = new RiskProfileLimits(BigDecimal.ONE, 10, new BigDecimal("4"));

    @ParameterizedTest(name = "position {0}, total {1}: {2}")
    @CsvSource({
        "10, 40, ''",
        "10.01, 40, OVERSIZED_POSITION",
        "10, 40.01, TOTAL_OPEN_RISK",
        "12, 45, OVERSIZED_POSITION TOTAL_OPEN_RISK"
    })
    void BR29_D124_aWidenedStop_warnsAboveEachLimit_andAtTheLimitPasses(
            BigDecimal positionRisk, BigDecimal totalRisk, String expected) {
        assertThat(WidenedStopRule.warnings(positionRisk, totalRisk, CAPITAL, LIMITS))
                .extracting(PlanWarning::type)
                .containsExactly(
                        expected.isBlank()
                                ? new WarningType[0]
                                : java.util.Arrays.stream(expected.split(" "))
                                        .map(WarningType::valueOf)
                                        .toArray(WarningType[]::new));
    }

    @Test
    void BR29_theWarnings_areNotBlocking_andNameTheShares() {
        assertThat(WidenedStopRule.warnings(new BigDecimal("12"), new BigDecimal("45"), CAPITAL, LIMITS))
                .allSatisfy(warning -> assertThat(warning.severity()).isEqualTo(WarningSeverity.WARNING))
                .extracting(PlanWarning::message)
                .containsExactly(
                        "the position's risk at the new stop loss, 1.2% of capital, is above the risk profile's 1% per"
                                + " trade",
                        "the total open risk with the new stop loss, 4.5% of capital, is above the risk profile's"
                                + " maximum of 4%");
    }

    @Test
    void D121_aCapitalNotAboveZero_isRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> WidenedStopRule.warnings(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, LIMITS));
    }
}
