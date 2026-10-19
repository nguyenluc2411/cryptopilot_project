package com.cryptopilot.trading.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.FeeSchedule;
import com.cryptopilot.trading.model.LeverageBracket;
import com.cryptopilot.trading.model.TradeClose;
import com.cryptopilot.trading.model.TradeResult;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Realized profit and loss, fees and net of a closed trade against the amounts of the T-044 acceptance criteria and
 * the TECHNICAL_DESIGN 7.8 worked example.
 *
 * <p>Rule: BR-35, BR-36, BR-38; D-97; Q-54; TECHNICAL_DESIGN 7.8.
 */
class TradeResultCalculatorTest {

    private static final FeeSchedule FUTURES = new FeeSchedule(rate("0.0002"), rate("0.0005"), rate("0.0125"));
    private static final FeeSchedule SPOT = new FeeSchedule(rate("0.001"), rate("0.001"), null);

    private static final LeverageBracket B1 = bracket(1, "0", "50000", "0.004", "0");
    private static final LeverageBracket B2 = bracket(2, "50000", "250000", "0.005", "50");
    private static final List<LeverageBracket> BRACKETS = List.of(B1, B2);

    // ------------------------------------------------------------------ T-044 acceptance cases

    /** (a) LONG Futures TP, qty 2, LIMIT 100, TP 115: realized 30, fee 0.04 + 0.046, net 29.914. */
    @Test
    void BR36_longFuturesTakeProfit_paysMakerOnBothFills() {
        TradeResult r = calculate(
                MarketType.FUTURES,
                Direction.LONG,
                EntryType.LIMIT,
                "100",
                "2",
                CloseReason.TAKE_PROFIT,
                "115",
                FUTURES,
                List.of());

        assertAmounts(r, "30", "0.086", "29.914");
        assertThat(r.entryFee()).isEqualByComparingTo("0.04");
        assertThat(r.exitFee()).isEqualByComparingTo("0.046");
    }

    /** (b) SHORT Futures SL, qty 2, MARKET 100, SL 105: realized −10, fee 0.1 + 0.105, net −10.205. */
    @Test
    void BR36_shortFuturesStopLoss_paysTakerOnBothFills() {
        TradeResult r = calculate(
                MarketType.FUTURES,
                Direction.SHORT,
                EntryType.MARKET,
                "100",
                "2",
                CloseReason.STOP_LOSS,
                "105",
                FUTURES,
                List.of());

        assertAmounts(r, "-10", "0.205", "-10.205");
    }

    /**
     * (c) LONG Futures liquidation, qty 2, LIMIT 100, 10x, MMR 0.004, LP 90.37: realized −19.26; clearance
     * min(180.74 × 1.25 % = 2.25925, MM 0.72296) = 0.72296, no taker; fee 0.04 + 0.72296; net −20.02296.
     */
    @Test
    void BR36_longLiquidation_paysTheClearanceFeeCappedAtTheMaintenanceMargin() {
        TradeResult r = calculate(
                MarketType.FUTURES,
                Direction.LONG,
                EntryType.LIMIT,
                "100",
                "2",
                CloseReason.LIQUIDATION,
                "90.37",
                FUTURES,
                BRACKETS);

        assertThat(r.realizedPnl()).isEqualByComparingTo("-19.26");
        assertThat(r.exitFee()).isEqualByComparingTo("0.72296");
        assertThat(r.feeAmount()).isEqualByComparingTo("0.76296");
        assertThat(r.netPnl()).isEqualByComparingTo("-20.02296");
    }

    /** (d) Spot manual close, LONG qty 0.5, MARKET 60000, close 62000: realized 1000, fee 30 + 31, net 939. */
    @Test
    void BR36_spotManualClose_paysTheSpotTakerRate() {
        TradeResult r = calculate(
                MarketType.SPOT,
                Direction.LONG,
                EntryType.MARKET,
                "60000",
                "0.5",
                CloseReason.MANUAL,
                "62000",
                SPOT,
                List.of());

        assertAmounts(r, "1000", "61", "939");
    }

    // ------------------------------------------------------------------ further cases

    /** A manual Futures close after a LIMIT entry: maker in, taker out; net = realized − fees. */
    @Test
    void BR36_futuresManualClose_paysMakerInAndTakerOut() {
        TradeResult r = calculate(
                MarketType.FUTURES,
                Direction.LONG,
                EntryType.LIMIT,
                "100",
                "2",
                CloseReason.MANUAL,
                "110",
                FUTURES,
                List.of());

        // 0.04 + 220 × 0.0005 = 0.11
        assertAmounts(r, "20", "0.15", "19.85");
    }

    /** SHORT liquidation at 109.56: N 219.12, MM 0.87648 below 2.739; taker entry 0.1. */
    @Test
    void BR36_shortLiquidation_paysTheMaintenanceMargin() {
        TradeResult r = calculate(
                MarketType.FUTURES,
                Direction.SHORT,
                EntryType.MARKET,
                "100",
                "2",
                CloseReason.LIQUIDATION,
                "109.56",
                FUTURES,
                BRACKETS);

        assertAmounts(r, "-19.12", "0.97648", "-20.09648");
    }

    /** MMR 2 % above the 1.25 % rate: N 90, MM 1.8, fee 1.125; 0.675 of MM stays with the trader. */
    @Test
    void BR36_bracketAboveTheRate_paysNotionalTimesRateAndKeepsTheRest() {
        List<LeverageBracket> wide = List.of(bracket(1, "0", "1000", "0.02", "0"));
        TradeResult r = calculate(
                MarketType.FUTURES,
                Direction.LONG,
                EntryType.LIMIT,
                "100",
                "1",
                CloseReason.LIQUIDATION,
                "90",
                FUTURES,
                wide);

        assertThat(r.exitFee()).isEqualByComparingTo("1.125");
        assertThat(new BigDecimal("1.8").subtract(r.exitFee())).isEqualByComparingTo("0.675");
        assertAmounts(r, "-10", "1.145", "-11.145");
    }

    /** The bracket is the one of the notional at the liquidation price, with its derived maintenance amount. */
    @Test
    void BR27_clearanceFee_usesTheBracketOfTheLiquidationNotional() {
        List<LeverageBracket> derived = List.of(B1, B2.withMaintenanceAmount(null));

        // 60000 × 0.005 − 50 = 250, below 60000 × 0.0125 = 750
        assertThat(TradeResultCalculator.clearanceFee(new BigDecimal("60000"), rate("0.0125"), BRACKETS))
                .isEqualByComparingTo("250");
        assertThat(TradeResultCalculator.clearanceFee(new BigDecimal("60000"), rate("0.0125"), derived))
                .isEqualByComparingTo("250");
    }

    /** No maintenance margin left (a source amount above N × MMR): nothing is charged. */
    @Test
    void BR36_noMaintenanceMarginLeft_paysNoClearanceFee() {
        List<LeverageBracket> odd = List.of(bracket(1, "0", "1000", "0.01", "5"));

        assertThat(TradeResultCalculator.clearanceFee(new BigDecimal("100"), rate("0.0125"), odd))
                .isZero();
    }

    @Test
    void Q58_notionalAboveTheLargestBracket_isRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> TradeResultCalculator.clearanceFee(new BigDecimal("250000"), rate("0.0125"), BRACKETS))
                .withMessageContaining("above the largest bracket");
    }

    @Test
    void BR35_spotLiquidation_isRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> close(MarketType.SPOT, CloseReason.LIQUIDATION, FUTURES, BRACKETS))
                .withMessageContaining("Futures");
    }

    @Test
    void BR36_liquidationWithoutClearanceRateOrBrackets_isRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> close(MarketType.FUTURES, CloseReason.LIQUIDATION, SPOT, BRACKETS));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> close(MarketType.FUTURES, CloseReason.LIQUIDATION, FUTURES, List.of()));
    }

    @Test
    void BR35_zeroQuantity_isRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TradeClose(
                        MarketType.SPOT,
                        Direction.LONG,
                        EntryType.MARKET,
                        BigDecimal.ONE,
                        BigDecimal.ZERO,
                        CloseReason.MANUAL,
                        BigDecimal.ONE,
                        SPOT,
                        List.of()));
    }

    @Test
    void BR27_maintenanceMargin_needsTheMaintenanceAmount() {
        assertThatIllegalStateException()
                .isThrownBy(() -> B2.withMaintenanceAmount(null).maintenanceMargin(BigDecimal.TEN));
    }

    private static TradeClose close(
            MarketType market, CloseReason reason, FeeSchedule fees, List<LeverageBracket> brackets) {
        return new TradeClose(
                market,
                Direction.LONG,
                EntryType.MARKET,
                BigDecimal.TEN,
                BigDecimal.ONE,
                reason,
                BigDecimal.ONE,
                fees,
                brackets);
    }

    private static TradeResult calculate(
            MarketType market,
            Direction direction,
            EntryType entryType,
            String entry,
            String qty,
            CloseReason reason,
            String exit,
            FeeSchedule fees,
            List<LeverageBracket> brackets) {
        return TradeResultCalculator.calculate(new TradeClose(
                market,
                direction,
                entryType,
                new BigDecimal(entry),
                new BigDecimal(qty),
                reason,
                new BigDecimal(exit),
                fees,
                brackets));
    }

    private static void assertAmounts(TradeResult r, String realized, String fee, String net) {
        assertThat(r.realizedPnl()).isEqualByComparingTo(realized);
        assertThat(r.feeAmount()).isEqualByComparingTo(fee);
        assertThat(r.netPnl()).isEqualByComparingTo(net);
        assertThat(r.realizedPnl().scale()).isEqualTo(8);
    }

    private static BigDecimal rate(String value) {
        return new BigDecimal(value);
    }

    private static LeverageBracket bracket(int no, String floor, String cap, String mmr, String cum) {
        return new LeverageBracket(
                no,
                new BigDecimal(floor),
                new BigDecimal(cap),
                125,
                new BigDecimal(mmr),
                cum == null ? null : new BigDecimal(cum));
    }
}
