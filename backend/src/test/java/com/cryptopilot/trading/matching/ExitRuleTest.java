package com.cryptopilot.trading.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.Exit;
import com.cryptopilot.trading.model.OpenPosition;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Take profit, stop loss and their precedence by the last price range; liquidation by the mark price; gaps; the candle
 * the entry filled in.
 *
 * <p>Rule: BR-35; TECHNICAL_DESIGN 7.7; Q-53, Q-59, Q-61; D-112 R5; team lead decision of 2026-10-24 (a take profit is a
 * resting limit order and fills at its price also through a gap).
 */
class ExitRuleTest {

    private static final UUID PLAN = UUID.randomUUID();
    private static final UUID PAIR = UUID.randomUUID();
    private static final Instant FROM = Instant.parse("2026-10-19T10:00:00Z");
    private static final Instant AT = Instant.parse("2026-10-19T10:00:30Z");

    /** Filled an hour before the candles of the tests, so they are ordinary candles. */
    private static final Instant EARLIER = FROM.minus(Duration.ofHours(1));

    private static final OpenPosition LONG = position(Direction.LONG, "100", EARLIER, "115", "95", "90.37");
    private static final OpenPosition SHORT = position(Direction.SHORT, "100", EARLIER, "85", "105", "109.56");

    @Test
    void BR35_longStopLoss_reachedAtItsLevel_exitsAtTheStop() {
        assertExit(ExitRule.byRange(LONG, range(null, "95", "100")), CloseReason.STOP_LOSS, "95");
    }

    @Test
    void BR35_longTakeProfit_reachedAtItsLevel_exitsAtTheTarget() {
        assertExit(ExitRule.byRange(LONG, range(null, "100", "115")), CloseReason.TAKE_PROFIT, "115");
    }

    @Test
    void BR35_bothReachedInOneRange_appliesTheStopLoss() {
        assertExit(ExitRule.byRange(LONG, range(null, "94", "116")), CloseReason.STOP_LOSS, "95");
        assertExit(ExitRule.byRange(SHORT, range(null, "84", "106")), CloseReason.STOP_LOSS, "105");
    }

    @Test
    void Q61_bothReached_openInsideTheLevels_appliesTheStopLoss() {
        assertExit(ExitRule.byRange(LONG, range("100", "94", "116")), CloseReason.STOP_LOSS, "95");
        assertExit(ExitRule.byRange(SHORT, range("100", "84", "106")), CloseReason.STOP_LOSS, "105");
    }

    @Test
    void Q61_openBeyondTheTakeProfit_decides_evenWhenTheCandleLaterReachesTheStop() {
        assertExit(ExitRule.byRange(LONG, range("118", "94", "120")), CloseReason.TAKE_PROFIT, "115");
        assertExit(ExitRule.byRange(SHORT, range("83", "80", "106")), CloseReason.TAKE_PROFIT, "85");
    }

    @Test
    void Q61_openBeyondTheStop_decides_atTheOpen_evenWhenTheCandleLaterReachesTheTakeProfit() {
        assertExit(ExitRule.byRange(LONG, range("93", "90", "116")), CloseReason.STOP_LOSS, "93");
        assertExit(ExitRule.byRange(SHORT, range("107", "84", "110")), CloseReason.STOP_LOSS, "107");
    }

    @Test
    void Q61_openExactlyAtALevel_decidesByThatLevel() {
        assertExit(ExitRule.byRange(LONG, range("115", "94", "115")), CloseReason.TAKE_PROFIT, "115");
        assertExit(ExitRule.byRange(LONG, range("95", "95", "116")), CloseReason.STOP_LOSS, "95");
        assertExit(ExitRule.byRange(SHORT, range("85", "85", "106")), CloseReason.TAKE_PROFIT, "85");
        assertExit(ExitRule.byRange(SHORT, range("105", "84", "105")), CloseReason.STOP_LOSS, "105");
    }

    @Test
    void BR35_shortLevels_areTheMirror() {
        assertExit(ExitRule.byRange(SHORT, range(null, "100", "105")), CloseReason.STOP_LOSS, "105");
        assertExit(ExitRule.byRange(SHORT, range(null, "85", "100")), CloseReason.TAKE_PROFIT, "85");
    }

    @Test
    void BR35_rangeInsideTheLevels_doesNotExit() {
        assertThat(ExitRule.byRange(LONG, range("100", "95.01", "114.99"))).isEmpty();
        assertThat(ExitRule.byRange(SHORT, range("100", "85.01", "104.99"))).isEmpty();
    }

    @Test
    void BR35_positionWithoutLevels_neverExitsByRange() {
        OpenPosition bare = position(Direction.LONG, "100", EARLIER, null, null, null);

        assertThat(ExitRule.byRange(bare, range("1", "1", "1000"))).isEmpty();
    }

    @Test
    void BR35_lastPriceRange_neverLiquidates() {
        OpenPosition noStop = position(Direction.LONG, "100", EARLIER, null, null, "90.37");

        assertThat(ExitRule.byRange(noStop, range(null, "80", "100"))).isEmpty();
    }

    @Test
    void Q53_candleOpenedBeyondTheStop_fillsAtTheOpen() {
        assertExit(ExitRule.byRange(LONG, range("93", "90", "93")), CloseReason.STOP_LOSS, "93");
        assertExit(ExitRule.byRange(SHORT, range("107", "106", "110")), CloseReason.STOP_LOSS, "107");
    }

    /** Team lead, 2026-10-24: a resting take profit (maker, D-97) fills at its own price through a gap, as E7. */
    @Test
    void BR36_candleOpenedBeyondTheTakeProfit_fillsAtTheTakeProfit_notAtTheBetterOpen() {
        assertExit(ExitRule.byRange(LONG, range("118", "117", "120")), CloseReason.TAKE_PROFIT, "115");
        assertExit(ExitRule.byRange(SHORT, range("83", "80", "84")), CloseReason.TAKE_PROFIT, "85");
    }

    @Test
    void Q53_candleOpenedInsideTheLevels_fillsAtTheLevel() {
        assertExit(ExitRule.byRange(LONG, range("99", "94", "100")), CloseReason.STOP_LOSS, "95");
        assertExit(ExitRule.byRange(SHORT, range("88", "84", "90")), CloseReason.TAKE_PROFIT, "85");
    }

    /** Q-59: a STOP filled at the candle's open can reach its stop loss in that candle, at the stop. */
    @Test
    void Q59_entryCandle_stopFilledAtTheOpen_reachesItsStopLossInThatCandle() {
        OpenPosition stopLong = position(Direction.LONG, "105.1", FROM, "125", "95", null);
        OpenPosition stopShort = position(Direction.SHORT, "94.9", FROM, "75", "105", null);

        assertExit(ExitRule.byRange(stopLong, range("105", "94", "106")), CloseReason.STOP_LOSS, "95");
        assertExit(ExitRule.byRange(stopShort, range("95", "94", "106")), CloseReason.STOP_LOSS, "105");
    }

    /** Q-59, D-112 R5: the order inside the entry candle is unknown, so its favourable extreme takes no profit. */
    @Test
    void Q59_entryCandle_neverTakesProfit() {
        OpenPosition stopLong = position(Direction.LONG, "105.1", FROM, "125", "95", null);
        OpenPosition stopShort = position(Direction.SHORT, "94.9", FROM, "75", "105", null);

        assertThat(ExitRule.byRange(stopLong, range("105", "100", "130"))).isEmpty();
        assertThat(ExitRule.byRange(stopShort, range("95", "70", "100"))).isEmpty();
        // Both reached in the entry candle: only the stop loss can act.
        assertExit(ExitRule.byRange(stopLong, range("105", "94", "130")), CloseReason.STOP_LOSS, "95");
    }

    /**
     * Q-59, D-123 safety net: activation refuses a plan whose stop loss the last price has reached, but a replay edge
     * case can still open a position past its stop; it exits at its entry price, not at the better stop.
     */
    @Test
    void Q59_entryCandle_filledBeyondItsStop_exitsAtTheEntryPrice() {
        OpenPosition limitLong = position(Direction.LONG, "94.1", AT, "115", "95", null);
        OpenPosition limitShort = position(Direction.SHORT, "105.9", AT, "85", "105", null);

        assertExit(ExitRule.byRange(limitLong, range("97", "94.1", "97")), CloseReason.STOP_LOSS, "94.1");
        assertExit(ExitRule.byRange(limitShort, range("103", "103", "105.9")), CloseReason.STOP_LOSS, "105.9");
    }

    /** Q-59: inside the entry candle a fill short of the stop exits at the stop once the candle reaches it. */
    @Test
    void Q59_entryCandle_filledShortOfItsStop_exitsAtTheStop() {
        OpenPosition limitLong = position(Direction.LONG, "100", AT, "115", "95", null);

        assertExit(ExitRule.byRange(limitLong, range("93", "93", "101")), CloseReason.STOP_LOSS, "95");
        assertThat(ExitRule.byRange(limitLong, range("93", "95.01", "101"))).isEmpty();
    }

    @Test
    void Q59_candleThatEndedBeforeTheEntry_neverActsOnThePosition() {
        OpenPosition next = position(Direction.LONG, "100", FROM.plus(Duration.ofMinutes(1)), "115", "95", "90.37");

        assertThat(ExitRule.actsOn(next, range(null, "80", "130"))).isFalse();
        assertThat(ExitRule.byRange(next, range(null, "80", "130"))).isEmpty();
        assertThat(ExitRule.byMark(next, marks("80", "80"))).isEmpty();
    }

    @Test
    void Q59_entryCandle_isTheCandleThatOpenedAtOrBeforeTheEntryAndEndedAfterIt() {
        assertThat(ExitRule.isEntryCandle(entryAt(FROM), range(null, "1", "2"))).isTrue();
        assertThat(ExitRule.isEntryCandle(entryAt(FROM.plusSeconds(59)), range(null, "1", "2")))
                .isTrue();
        assertThat(ExitRule.isEntryCandle(entryAt(FROM.minusSeconds(1)), range(null, "1", "2")))
                .isFalse();
        assertThat(ExitRule.isEntryCandle(entryAt(FROM.plusSeconds(60)), range(null, "1", "2")))
                .isFalse();
    }

    @Test
    void Q53_markGappedPastTheLiquidationPrice_stillLiquidatesAtThatPrice() {
        assertMark(ExitRule.byMark(LONG, marks("80", "85")), "90.37");
        assertMark(ExitRule.byMark(SHORT, marks("125", "130")), "109.56");
    }

    @Test
    void BR35_markAtOrBeyondTheLiquidationPrice_liquidatesAtThatPrice() {
        assertMark(ExitRule.byMark(LONG, marks("90.37", "95")), "90.37");
        assertMark(ExitRule.byMark(LONG, marks("89", "89")), "90.37");
        assertMark(ExitRule.byMark(SHORT, marks("100", "109.56")), "109.56");
    }

    @Test
    void BR35_markShortOfTheLiquidationPrice_doesNotLiquidate() {
        assertThat(ExitRule.byMark(LONG, marks("90.38", "120"))).isEmpty();
        assertThat(ExitRule.byMark(SHORT, marks("80", "109.55"))).isEmpty();
    }

    /** The mark price of the entry candle can liquidate: only its adverse extreme is read. */
    @Test
    void BR35_markInTheEntryCandle_liquidates() {
        OpenPosition filledNow = position(Direction.LONG, "100", AT, "115", "95", "90.37");

        assertMark(ExitRule.byMark(filledNow, marks("90", "100")), "90.37");
    }

    @Test
    void BR27_positionWithoutLiquidationPrice_isNeverLiquidated() {
        assertThat(ExitRule.byMark(position(Direction.LONG, "100", EARLIER, null, null, null), marks("1", "1")))
                .isEmpty();
        assertThat(ExitRule.byMark(position(Direction.LONG, "100", EARLIER, null, null, "0"), marks("0.0001", "1")))
                .isEmpty();
    }

    /** D-111 E1: a take profit rested, so it is maker; a stop loss and a manual close took liquidity. */
    @Test
    void BR36_theLiquidityRoleOfAnExit_followsWhetherItRested() {
        assertThat(ExitRule.liquidity(CloseReason.TAKE_PROFIT)).isEqualTo(LiquidityRole.MAKER);
        assertThat(ExitRule.liquidity(CloseReason.STOP_LOSS)).isEqualTo(LiquidityRole.TAKER);
        assertThat(ExitRule.liquidity(CloseReason.MANUAL)).isEqualTo(LiquidityRole.TAKER);
        assertThatIllegalArgumentException().isThrownBy(() -> ExitRule.liquidity(CloseReason.LIQUIDATION));
    }

    private static void assertExit(Optional<Exit> exit, CloseReason reason, String price) {
        assertThat(exit).hasValueSatisfying(e -> {
            assertThat(e.planId()).isEqualTo(PLAN);
            assertThat(e.reason()).isEqualTo(reason);
            assertThat(e.price()).isEqualByComparingTo(price);
            assertThat(e.exitedAt()).isEqualTo(FROM);
        });
    }

    private static void assertMark(Optional<Exit> exit, String price) {
        assertExit(exit, CloseReason.LIQUIDATION, price);
    }

    private static PriceRange range(String open, String low, String high) {
        return new PriceRange(
                MarketType.FUTURES, PAIR, decimal(open), new BigDecimal(low), new BigDecimal(high), FROM, AT, false);
    }

    private static PriceRange marks(String low, String high) {
        return PriceRange.mark(PAIR, new BigDecimal(low), new BigDecimal(high), FROM, AT, false);
    }

    private static OpenPosition entryAt(Instant entryTime) {
        return position(Direction.LONG, "100", entryTime, null, null, null);
    }

    private static OpenPosition position(
            Direction direction, String entry, Instant entryTime, String tp, String sl, String lp) {
        return new OpenPosition(
                PLAN, direction, new BigDecimal(entry), entryTime, decimal(tp), decimal(sl), decimal(lp));
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
