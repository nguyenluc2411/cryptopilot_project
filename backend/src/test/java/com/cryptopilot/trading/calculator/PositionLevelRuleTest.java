package com.cryptopilot.trading.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.trading.model.InputViolation;
import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * TP-E: the checks of a new stop loss or take profit of an open position against the current last price and the
 * liquidation price, and the risk an open position carries at its current stop.
 *
 * <p>Rule: BR-22, BR-28, BR-29, BR-42; D-124.
 */
class PositionLevelRuleTest {

    private static final BigDecimal LAST = new BigDecimal("100");

    @ParameterizedTest(name = "{0} stop loss {1}, take profit {2}: {3}")
    @CsvSource({
        "LONG,  99.99,  100.01, ''",
        "LONG,  100,    ,       stopLoss",
        "LONG,  100.5,  ,       stopLoss",
        "LONG,  ,       100,    takeProfit",
        "LONG,  ,       99,     takeProfit",
        "LONG,  101,    99,     stopLoss takeProfit",
        "SHORT, 100.01, 99.99,  ''",
        "SHORT, 100,    ,       stopLoss",
        "SHORT, 99,     ,       stopLoss",
        "SHORT, ,       100,    takeProfit",
        "SHORT, ,       101,    takeProfit",
        "LONG,  ,       ,       ''"
    })
    void BR22_D124_aNewLevel_mustLieOnItsSideOfTheLastPrice_andNotBeReached(
            Direction direction, BigDecimal stopLoss, BigDecimal takeProfit, String refused) {
        List<InputViolation> violations = PositionLevelRule.violations(direction, stopLoss, takeProfit, LAST, null);

        assertThat(violations).extracting(InputViolation::field).containsExactly(fields(refused));
        assertThat(violations).extracting(InputViolation::messageCode).allMatch("MSG16"::equals);
    }

    @ParameterizedTest(name = "{0} stop loss {1}, liquidation {2}: refused {3}")
    @CsvSource({
        "LONG,  90,    90,  true",
        "LONG,  89,    90,  true",
        "LONG,  90.01, 90,  false",
        "LONG,  50,    0,   false",
        "SHORT, 110,   110, true",
        "SHORT, 111,   110, true",
        "SHORT, 109.9, 110, false"
    })
    void BR28_D124_aFuturesStopLoss_mustStayBeforeTheLiquidationPrice(
            Direction direction, BigDecimal stopLoss, BigDecimal liquidation, boolean refused) {
        List<InputViolation> violations = PositionLevelRule.violations(direction, stopLoss, null, LAST, liquidation);

        assertThat(violations)
                .extracting(InputViolation::field)
                .containsExactly(refused ? fields("stopLoss") : fields(""));
    }

    @Test
    void BR28_withoutALiquidationPrice_aStopLossIsCheckedAgainstTheLastPriceOnly() {
        assertThat(PositionLevelRule.violations(Direction.LONG, new BigDecimal("1"), null, LAST, null))
                .isEmpty();
    }

    @ParameterizedTest(name = "{0} entry 100, stop loss {1}, quantity 2: risk {2}")
    @CsvSource({"LONG,  95,  10", "LONG,  100, 0", "LONG,  101, 0", "SHORT, 104, 8", "SHORT, 100, 0", "SHORT, 99,  0"})
    void BR29_D124_theCurrentRisk_isTheLossToTheStop_andZeroAtOrPastTheEntry(
            Direction direction, BigDecimal stopLoss, BigDecimal risk) {
        assertThat(PositionLevelRule.currentRisk(direction, new BigDecimal("100"), stopLoss, new BigDecimal("2")))
                .isEqualByComparingTo(risk);
    }

    @Test
    void BR29_aPositionWithoutAStopLoss_countsNoRisk() {
        assertThat(PositionLevelRule.currentRisk(Direction.LONG, new BigDecimal("100"), null, BigDecimal.ONE))
                .isZero();
    }

    @ParameterizedTest(name = "{0} stop {1} against {2}: further {3}")
    @CsvSource({
        "LONG,  94, 95, true",
        "LONG,  95, 95, false",
        "LONG,  96, 95, false",
        "SHORT, 106, 105, true",
        "SHORT, 105, 105, false",
        "SHORT, 104, 105, false",
        "LONG,  , 95, false",
        "LONG,  94, , false"
    })
    void BR42_D124_aStopIsFurtherFromTheEntry_whenItLeavesMoreRoomToLose(
            Direction direction, BigDecimal stopLoss, BigDecimal reference, boolean further) {
        assertThat(PositionLevelRule.isFurtherFromEntry(direction, stopLoss, reference))
                .isEqualTo(further);
    }

    private static String[] fields(String refused) {
        return refused.isBlank() ? new String[0] : refused.trim().split(" ");
    }
}
