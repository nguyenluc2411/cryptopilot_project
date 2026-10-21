package com.cryptopilot.trading.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.Exit;
import com.cryptopilot.trading.model.OpenPosition;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Take profit, stop loss and their precedence by the last price range; liquidation by the mark price; gaps.
 *
 * <p>Rule: BR-35; TECHNICAL_DESIGN 7.7; Q-53, Q-59.
 */
class ExitRuleTest {

    private static final UUID PLAN = UUID.randomUUID();
    private static final Instant FROM = Instant.parse("2026-10-19T10:00:00Z");
    private static final Instant AT = Instant.parse("2026-10-19T10:00:30Z");

    private static final OpenPosition LONG = position(Direction.LONG, "115", "95", "90.37");
    private static final OpenPosition SHORT = position(Direction.SHORT, "85", "105", "109.56");

    @Test
    void BR35_longStopLoss_reachedAtItsLevel_exitsAtTheStop() {
        assertExit(ExitRule.byRange(LONG, range("95", "100"), null), CloseReason.STOP_LOSS, "95", FROM);
    }

    @Test
    void BR35_longTakeProfit_reachedAtItsLevel_exitsAtTheTarget() {
        assertExit(ExitRule.byRange(LONG, range("100", "115"), null), CloseReason.TAKE_PROFIT, "115", FROM);
    }

    @Test
    void BR35_bothReachedInOneRange_appliesTheStopLoss() {
        assertExit(ExitRule.byRange(LONG, range("94", "116"), null), CloseReason.STOP_LOSS, "95", FROM);
        assertExit(ExitRule.byRange(SHORT, range("84", "106"), null), CloseReason.STOP_LOSS, "105", FROM);
    }

    @Test
    void BR35_shortLevels_areTheMirror() {
        assertExit(ExitRule.byRange(SHORT, range("100", "105"), null), CloseReason.STOP_LOSS, "105", FROM);
        assertExit(ExitRule.byRange(SHORT, range("85", "100"), null), CloseReason.TAKE_PROFIT, "85", FROM);
    }

    @Test
    void BR35_rangeInsideTheLevels_doesNotExit() {
        assertThat(ExitRule.byRange(LONG, range("95.01", "114.99"), null)).isEmpty();
        assertThat(ExitRule.byRange(SHORT, range("85.01", "104.99"), null)).isEmpty();
    }

    @Test
    void BR35_positionWithoutLevels_neverExitsByRange() {
        OpenPosition bare = position(Direction.LONG, null, null, null);

        assertThat(ExitRule.byRange(bare, range("1", "1000"), null)).isEmpty();
    }

    @Test
    void BR35_lastPriceRange_neverLiquidates() {
        OpenPosition noStop = position(Direction.LONG, null, null, "90.37");

        assertThat(ExitRule.byRange(noStop, range("80", "100"), null)).isEmpty();
    }

    @Test
    void Q53_candleOpenedBeyondTheStop_fillsAtTheOpen() {
        assertExit(ExitRule.byRange(LONG, range("90", "93"), decimal("93")), CloseReason.STOP_LOSS, "93", FROM);
        assertExit(ExitRule.byRange(SHORT, range("106", "110"), decimal("107")), CloseReason.STOP_LOSS, "107", FROM);
    }

    @Test
    void Q53_candleOpenedBeyondTheTarget_fillsAtTheOpen() {
        assertExit(ExitRule.byRange(LONG, range("117", "120"), decimal("118")), CloseReason.TAKE_PROFIT, "118", FROM);
        assertExit(ExitRule.byRange(SHORT, range("80", "84"), decimal("83")), CloseReason.TAKE_PROFIT, "83", FROM);
    }

    @Test
    void Q53_candleOpenedInsideOrAtTheLevel_fillsAtTheLevel() {
        assertExit(ExitRule.byRange(LONG, range("94", "100"), decimal("99")), CloseReason.STOP_LOSS, "95", FROM);
        assertExit(ExitRule.byRange(LONG, range("95", "100"), decimal("95")), CloseReason.STOP_LOSS, "95", FROM);
        assertExit(ExitRule.byRange(SHORT, range("84", "90"), decimal("88")), CloseReason.TAKE_PROFIT, "85", FROM);
    }

    @Test
    void Q53_markGappedPastTheLiquidationPrice_stillLiquidatesAtThatPrice() {
        assertExit(ExitRule.byMark(LONG, decimal("80"), AT), CloseReason.LIQUIDATION, "90.37", AT);
        assertExit(ExitRule.byMark(SHORT, decimal("130"), AT), CloseReason.LIQUIDATION, "109.56", AT);
    }

    @Test
    void BR35_markAtOrBeyondTheLiquidationPrice_liquidatesAtThatPrice() {
        assertExit(ExitRule.byMark(LONG, new BigDecimal("90.37"), AT), CloseReason.LIQUIDATION, "90.37", AT);
        assertExit(ExitRule.byMark(LONG, new BigDecimal("89"), AT), CloseReason.LIQUIDATION, "90.37", AT);
        assertExit(ExitRule.byMark(SHORT, new BigDecimal("109.56"), AT), CloseReason.LIQUIDATION, "109.56", AT);
    }

    @Test
    void BR35_markShortOfTheLiquidationPrice_doesNotLiquidate() {
        assertThat(ExitRule.byMark(LONG, new BigDecimal("90.38"), AT)).isEmpty();
        assertThat(ExitRule.byMark(SHORT, new BigDecimal("109.55"), AT)).isEmpty();
    }

    @Test
    void BR27_positionWithoutLiquidationPrice_isNeverLiquidated() {
        assertThat(ExitRule.byMark(position(Direction.LONG, null, null, null), BigDecimal.ONE, AT))
                .isEmpty();
        assertThat(ExitRule.byMark(position(Direction.LONG, null, null, "0"), BigDecimal.ZERO, AT))
                .isEmpty();
    }

    private static void assertExit(Optional<Exit> exit, CloseReason reason, String price, Instant at) {
        assertThat(exit).hasValueSatisfying(e -> {
            assertThat(e.planId()).isEqualTo(PLAN);
            assertThat(e.reason()).isEqualTo(reason);
            assertThat(e.price()).isEqualByComparingTo(price);
            assertThat(e.exitedAt()).isEqualTo(at);
        });
    }

    private static PriceRange range(String low, String high) {
        return new PriceRange(
                MarketType.FUTURES, UUID.randomUUID(), new BigDecimal(low), new BigDecimal(high), FROM, AT, false);
    }

    private static OpenPosition position(Direction direction, String tp, String sl, String lp) {
        return new OpenPosition(PLAN, direction, decimal(tp), decimal(sl), decimal(lp));
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
