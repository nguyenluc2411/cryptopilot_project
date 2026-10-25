package com.cryptopilot.trading.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.FeeSchedule;
import com.cryptopilot.trading.model.FundingFee;
import com.cryptopilot.trading.model.LeverageBracket;
import com.cryptopilot.trading.model.LiquidationEstimate;
import com.cryptopilot.trading.model.LiquidationInput;
import com.cryptopilot.trading.model.TradeClose;
import com.cryptopilot.trading.model.TradeResult;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Realized profit and loss, fees and net of a closed trade against the amounts of the T-044 acceptance criteria and
 * the TECHNICAL_DESIGN 7.8 worked example.
 *
 * <p>Rule: BR-35, BR-36, BR-37, BR-38; D-97; Q-54, Q-58; A-05; TECHNICAL_DESIGN 7.8.
 */
class TradeResultCalculatorTest {

    private static final FeeSchedule FUTURES = new FeeSchedule(rate("0.0002"), rate("0.0005"), rate("0.0125"));
    private static final FeeSchedule SPOT = new FeeSchedule(rate("0.001"), rate("0.001"), null);

    private static final LeverageBracket B1 = bracket(1, "0", "50000", "0.004", "0");
    private static final LeverageBracket B2 = bracket(2, "50000", "250000", "0.005", "50");
    private static final List<LeverageBracket> BRACKETS = List.of(B1, B2);

    // ------------------------------------------------------------------ unrealized P/L (T-048a)

    /**
     * Q-55: the unrealized P/L at the mark price is the price result without fees, LONG (mark − entry) × qty and SHORT
     * mirrored, at the stored scale: LONG 2 at 100 marked 101.5 gains 3, SHORT loses 3; a SHORT marked 98.25 gains
     * 3.5; 0.123 at 60,000 marked 60,000.01 gains 0.00123.
     */
    @ParameterizedTest(name = "{0} {1} at {2} marked {3}: {4}")
    @CsvSource({
        "LONG, 2, 100, 101.5, 3.00000000",
        "SHORT, 2, 100, 101.5, -3.00000000",
        "SHORT, 2, 100, 98.25, 3.50000000",
        "LONG, 0.123, 60000, 60000.01, 0.00123000"
    })
    void Q55_theUnrealizedPnl_isThePriceResultAtTheMarkPrice_withoutFees(
            Direction direction, String quantity, String entry, String mark, String expected) {
        assertThat(TradeResultCalculator.unrealizedPnl(
                        direction, new BigDecimal(entry), new BigDecimal(mark), new BigDecimal(quantity)))
                .isEqualTo(new BigDecimal(expected));
    }

    // ------------------------------------------------------------------ T-044 acceptance cases

    /** (a) LONG Futures TP, qty 2, LIMIT 100, TP 115: realized 30, fee 0.04 + 0.046, net 29.914. */
    @Test
    void BR36_longFuturesTakeProfit_paysMakerOnBothFills() {
        TradeResult r = calculate(
                MarketType.FUTURES,
                Direction.LONG,
                LiquidityRole.MAKER,
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
                LiquidityRole.TAKER,
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
                LiquidityRole.MAKER,
                "100",
                "2",
                CloseReason.LIQUIDATION,
                "90.37",
                FUTURES,
                BRACKETS);

        assertThat(r.realizedPnl()).isEqualByComparingTo("-19.26");
        assertThat(r.exitFee()).isEqualByComparingTo("0.72296");
        assertThat(r.feeAmount()).isEqualByComparingTo("0.76296");
        assertThat(r.netPnl().amount()).isEqualByComparingTo("-20.02296");
    }

    /** (d) Spot manual close, LONG qty 0.5, MARKET 60000, close 62000: realized 1000, fee 30 + 31, net 939. */
    @Test
    void BR36_spotManualClose_paysTheSpotTakerRate() {
        TradeResult r = calculate(
                MarketType.SPOT,
                Direction.LONG,
                LiquidityRole.TAKER,
                "60000",
                "0.5",
                CloseReason.MANUAL,
                "62000",
                SPOT,
                List.of());

        assertAmounts(r, "1000", "61", "939");
    }

    // ------------------------------------------------------------------ T-045: the same cases with funding

    /**
     * TECHNICAL_DESIGN 7.8 test vector, case (a) with one settlement at mark 101, rate 0.0001: the LONG pays
     * 2 × 101 × 0.0001 = 0.0202; net 30 − 0.086 − 0.0202 = 29.8938.
     */
    @Test
    void BR38_longTakeProfitWithOneFunding_isTheDesignVector() {
        TradeResult r = withFunding(
                Direction.LONG, LiquidityRole.MAKER, CloseReason.TAKE_PROFIT, "115", List.of(), Direction.LONG);

        assertThat(r.fundingFeeAmount()).isEqualByComparingTo("-0.0202");
        assertAmounts(r, "30", "0.086", "29.8938");
    }

    /** Case (b) with the same settlement: the SHORT receives 0.0202; net −10.205 + 0.0202 = −10.1848. */
    @Test
    void BR38_shortStopLossWithOneFunding_receivesIt() {
        TradeResult r = withFunding(
                Direction.SHORT, LiquidityRole.TAKER, CloseReason.STOP_LOSS, "105", List.of(), Direction.SHORT);

        assertThat(r.fundingFeeAmount()).isEqualByComparingTo("0.0202");
        assertAmounts(r, "-10", "0.205", "-10.1848");
    }

    /** Case (c), the liquidation vector, with the same settlement: net −20.02296 − 0.0202 = −20.04316. */
    @Test
    void BR38_longLiquidationWithOneFunding_paysItOnTopOfTheClearanceFee() {
        TradeResult r = withFunding(
                Direction.LONG, LiquidityRole.MAKER, CloseReason.LIQUIDATION, "90.37", BRACKETS, Direction.LONG);

        assertThat(r.fundingFeeAmount()).isEqualByComparingTo("-0.0202");
        assertAmounts(r, "-19.26", "0.76296", "-20.04316");
    }

    /** Case (d): Spot has no funding, null and not 0, so net = realized − fees = 939. */
    @Test
    void BR37_spotTrade_hasNullFunding_andNetIsRealizedMinusFees() {
        TradeResult r = calculate(
                MarketType.SPOT,
                Direction.LONG,
                LiquidityRole.TAKER,
                "60000",
                "0.5",
                CloseReason.MANUAL,
                "62000",
                SPOT,
                List.of(),
                null);

        assertThat(r.fundingFeeAmount()).isNull();
        assertThat(r.amounts().fundingFeeAmount()).isNull();
        assertAmounts(r, "1000", "61", "939");
    }

    @Test
    void BR37_aSpotTradeWithFunding_orAFuturesTradeWithout_isRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> calculate(
                        MarketType.SPOT,
                        Direction.LONG,
                        LiquidityRole.TAKER,
                        "60000",
                        "0.5",
                        CloseReason.MANUAL,
                        "62000",
                        SPOT,
                        List.of(),
                        FundingFee.complete(BigDecimal.ZERO)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> calculate(
                        MarketType.FUTURES,
                        Direction.LONG,
                        LiquidityRole.MAKER,
                        "100",
                        "2",
                        CloseReason.TAKE_PROFIT,
                        "115",
                        FUTURES,
                        List.of(),
                        null));
    }

    /**
     * A-05, D-97 note of 2026-10-24: a Futures trade closes while its 16:00 settlement is missing. It carries the
     * known sum (the LONG paid 0.0202 at 08:00), the flag and the missing instant, and its net 30 − 0.086 − 0.0202 =
     * 29.8938 is provisional.
     */
    @Test
    void A05_aFuturesTradeWithIncompleteFunding_closes_withTheKnownSumAndAProvisionalNet() {
        Instant missing = Instant.parse("2026-10-20T16:00:00Z");
        FundingFee funding = FundingFee.partial(new BigDecimal("-0.02020000"), List.of(missing));

        TradeResult r = calculate(
                MarketType.FUTURES,
                Direction.LONG,
                LiquidityRole.MAKER,
                "100",
                "2",
                CloseReason.TAKE_PROFIT,
                "115",
                FUTURES,
                List.of(),
                funding);

        assertThat(r.fundingFeeAmount()).isEqualByComparingTo("-0.0202");
        assertThat(r.fundingIncomplete()).isTrue();
        assertThat(r.missingFundingTimes()).containsExactly(missing);
        assertThat(r.netPnl().amount()).isEqualByComparingTo("29.8938");
        assertThat(r.netPnl().provisional()).isTrue();
        assertThat(r.amounts().fundingIncomplete()).isTrue();
        assertThat(r.amounts().netPnl().provisional()).isTrue();
    }

    /** A complete funding gives a final net; Spot has no funding, so neither flag nor missing instants. */
    @Test
    void A05_aCompleteFunding_orASpotTrade_givesAFinalNet() {
        TradeResult futures = withFunding(
                Direction.LONG, LiquidityRole.MAKER, CloseReason.TAKE_PROFIT, "115", List.of(), Direction.LONG);
        TradeResult spot = calculate(
                MarketType.SPOT,
                Direction.LONG,
                LiquidityRole.TAKER,
                "60000",
                "0.5",
                CloseReason.MANUAL,
                "62000",
                SPOT,
                List.of(),
                null);

        assertThat(futures.fundingIncomplete()).isFalse();
        assertThat(futures.netPnl().provisional()).isFalse();
        assertThat(spot.funding()).isNull();
        assertThat(spot.fundingIncomplete()).isFalse();
        assertThat(spot.missingFundingTimes()).isEmpty();
        assertThat(spot.netPnl().provisional()).isFalse();
    }

    /** A Spot trade is refused an incomplete funding just as a complete one. */
    @Test
    void BR37_aSpotTradeWithIncompleteFunding_isRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> calculate(
                        MarketType.SPOT,
                        Direction.LONG,
                        LiquidityRole.TAKER,
                        "60000",
                        "0.5",
                        CloseReason.MANUAL,
                        "62000",
                        SPOT,
                        List.of(),
                        FundingFee.partial(BigDecimal.ZERO, List.of())));
    }

    /** Qty 2, entry 100, one settlement at mark 101 and rate 0.0001, signed for the position by the calculator. */
    private static TradeResult withFunding(
            Direction direction,
            LiquidityRole entryLiquidity,
            CloseReason reason,
            String exit,
            List<LeverageBracket> brackets,
            Direction fundingSide) {
        FundingFee funding = FundingFee.complete(Rounding.toAmount(FundingCalculator.settlementAmount(
                fundingSide, new BigDecimal("2"), new BigDecimal("101"), new BigDecimal("0.0001"))));
        return calculate(
                MarketType.FUTURES, direction, entryLiquidity, "100", "2", reason, exit, FUTURES, brackets, funding);
    }

    // ------------------------------------------------------------------ D-111 E1: the entry's liquidity role

    /**
     * TD 7.8: a LONG LIMIT 100 activated while the last price is 99.50 filled at once at 99.50 and took liquidity, so
     * its entry pays taker, 99.50 × 2 × 0.0005 = 0.0995 (by its entry type it paid maker, 0.0398). A LIMIT 100 reached
     * later by a candle rested first and pays maker, 100 × 2 × 0.0002 = 0.04, as case (a).
     */
    @ParameterizedTest(name = "{0} at {1}: entry fee {2}")
    @CsvSource({"TAKER, 99.50, 0.0995", "MAKER, 100, 0.04"})
    void D111_theEntryFee_followsTheLiquidityRoleOfTheFill(LiquidityRole role, String entry, String entryFee) {
        TradeResult r = calculate(
                MarketType.FUTURES,
                Direction.LONG,
                role,
                entry,
                "2",
                CloseReason.TAKE_PROFIT,
                "115",
                FUTURES,
                List.of());

        assertThat(r.entryFee()).isEqualByComparingTo(entryFee);
        assertThat(r.exitFee())
                .as("a take profit is maker whatever the entry was")
                .isEqualByComparingTo("0.046");
    }

    // ------------------------------------------------------------------ further cases

    /** A manual Futures close after a LIMIT entry: maker in, taker out; net = realized − fees. */
    @Test
    void BR36_futuresManualClose_paysMakerInAndTakerOut() {
        TradeResult r = calculate(
                MarketType.FUTURES,
                Direction.LONG,
                LiquidityRole.MAKER,
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
                LiquidityRole.TAKER,
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
                LiquidityRole.MAKER,
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

    /**
     * Review P1-1: LONG qty 1, LIMIT 50000 in bracket 2, 10x, LP 45175.88. N_liq 45175.88 lies in bracket 1, but MM
     * uses bracket 2, the bracket of the liquidation price: 45175.88 × 0.005 − 50 = 175.8794, below 564.6985. Bracket
     * 1 would give 180.70352, more than the 5000 − 4824.12 = 175.88 left in the position.
     */
    @Test
    void Q58_liquidationNotionalInALowerBracket_usesTheBracketOfTheLiquidationPrice() {
        assertThat(liquidation(Direction.LONG, "50000", 10).liquidationPrice()).isEqualByComparingTo("45175.88");

        TradeResult r = calculate(
                MarketType.FUTURES,
                Direction.LONG,
                LiquidityRole.MAKER,
                "50000",
                "1",
                CloseReason.LIQUIDATION,
                "45175.88",
                FUTURES,
                BRACKETS);

        // entry fee 50000 × 0.0002 = 10
        assertAmounts(r, "-4824.12", "185.8794", "-5009.9994");
        assertThat(r.exitFee()).isEqualByComparingTo("175.8794").isLessThanOrEqualTo(new BigDecimal("175.88"));
    }

    /** A SHORT whose N_liq is above the largest bracket pays the MM of its entry bracket instead of failing. */
    @Test
    void Q58_liquidationNotionalAboveTheLargestBracket_usesTheEntryBracket() {
        // 1 × 240000 in bracket 2; at 260000: MM = 1300 − 50 = 1250, below 260000 × 1.25 % = 3250
        TradeResult r = calculate(
                MarketType.FUTURES,
                Direction.SHORT,
                LiquidityRole.MAKER,
                "240000",
                "1",
                CloseReason.LIQUIDATION,
                "260000",
                FUTURES,
                BRACKETS);

        assertThat(r.exitFee()).isEqualByComparingTo("1250");
    }

    /** At each bracket boundary and either side of it, the fee is at most the initial margin plus realized. */
    @ParameterizedTest(name = "{0} {1} {2}x")
    @CsvSource({
        "LONG, 100, 10",
        "LONG, 49999.99, 10",
        "LONG, 50000, 10",
        "LONG, 50000.01, 10",
        "LONG, 49999.99, 50",
        "LONG, 50000, 50",
        "LONG, 249999.99, 20",
        "SHORT, 100, 10",
        "SHORT, 49999.99, 10",
        "SHORT, 50000, 10",
        "SHORT, 50000.01, 10",
        "SHORT, 49999.99, 50",
        "SHORT, 50000, 50",
        "SHORT, 249999.99, 20"
    })
    void Q58_liquidation_neverChargesMoreThanTheMarginLeft(Direction direction, String entry, int leverage) {
        LiquidationEstimate lp = liquidation(direction, entry, leverage);

        TradeResult r = calculate(
                MarketType.FUTURES,
                direction,
                LiquidityRole.MAKER,
                entry,
                "1",
                CloseReason.LIQUIDATION,
                lp.liquidationPrice().toPlainString(),
                FUTURES,
                BRACKETS);

        BigDecimal marginLeft = Rounding.toAmount(lp.initialMargin().add(r.realizedPnl()));
        assertThat(r.exitFee()).isPositive().isLessThanOrEqualTo(marginLeft);
    }

    /** With a derived maintenance amount the fee is the same as with the source one: 60000 × 0.005 − 50 = 250. */
    @Test
    void BR27_clearanceFee_derivesTheMaintenanceAmount() {
        BigDecimal n = new BigDecimal("60000");
        List<LeverageBracket> derived = List.of(B1, B2.withMaintenanceAmount(null));

        assertThat(TradeResultCalculator.clearanceFee(n, rate("0.0125"), bracketOf(BRACKETS, n)))
                .isEqualByComparingTo("250");
        assertThat(TradeResultCalculator.clearanceFee(n, rate("0.0125"), bracketOf(derived, n)))
                .isEqualByComparingTo("250");
    }

    /** No maintenance margin left (a source amount above N × MMR): nothing is charged. */
    @Test
    void BR36_noMaintenanceMarginLeft_paysNoClearanceFee() {
        LeverageBracket odd = bracket(1, "0", "1000", "0.01", "5");

        assertThat(TradeResultCalculator.clearanceFee(new BigDecimal("100"), rate("0.0125"), odd))
                .isZero();
    }

    /**
     * Worked by hand, scale 8 HALF_EVEN. qty 0.123456789, LIMIT 12345.67, manual close 12400: entry notional
     * 1524.15677625363 × 0.0002 = 0.304831355250726 → 0.30483136; exit 1530.8641836 × 0.0005 = 0.7654320918 →
     * 0.76543209; realized 54.33 × qty = 6.70740734637 → 6.70740735.
     */
    @Test
    void BR36_amountsWithMoreDecimals_roundToScale8() {
        TradeResult r = calculate(
                MarketType.FUTURES,
                Direction.LONG,
                LiquidityRole.MAKER,
                "12345.67",
                "0.123456789",
                CloseReason.MANUAL,
                "12400",
                FUTURES,
                List.of());

        assertThat(r.entryFee()).isEqualByComparingTo("0.30483136");
        assertThat(r.exitFee()).isEqualByComparingTo("0.76543209");
        assertAmounts(r, "6.70740735", "1.07026345", "5.63714390");
        assertThat(r.entryFee().scale()).isEqualTo(8);
        assertThat(r.exitFee().scale()).isEqualTo(8);
    }

    /**
     * A tie: 5 × 0.123456789 = 0.617283945 → 0.61728394 (HALF_UP would give 0.61728395). Entry 12.3456789 × 0.0002 =
     * 0.00246913578 → 0.00246914; exit 12.962962845 × 0.0002 = 0.002592592569 → 0.00259259.
     */
    @Test
    void BR36_tieAtTheNinthDecimal_roundsToEven() {
        TradeResult r = calculate(
                MarketType.FUTURES,
                Direction.LONG,
                LiquidityRole.MAKER,
                "100",
                "0.123456789",
                CloseReason.TAKE_PROFIT,
                "105",
                FUTURES,
                List.of());

        assertThat(r.realizedPnl()).isEqualByComparingTo("0.61728394");
        assertThat(r.entryFee()).isEqualByComparingTo("0.00246914");
        assertThat(r.exitFee()).isEqualByComparingTo("0.00259259");
    }

    /** N_liq 90.37 × 0.123456789 = 11.15679002193; MM × 0.004 = 0.04462716008772 → 0.04462716; realized → −1.18888888. */
    @Test
    void BR36_clearanceFee_roundsToScale8() {
        TradeResult r = calculate(
                MarketType.FUTURES,
                Direction.LONG,
                LiquidityRole.MAKER,
                "100",
                "0.123456789",
                CloseReason.LIQUIDATION,
                "90.37",
                FUTURES,
                BRACKETS);

        assertThat(r.exitFee()).isEqualByComparingTo("0.04462716");
        assertThat(r.realizedPnl()).isEqualByComparingTo("-1.18888888");
    }

    @Test
    void BR27_entryNotionalOutsideTheBrackets_isRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> calculate(
                        MarketType.FUTURES,
                        Direction.LONG,
                        LiquidityRole.MAKER,
                        "250000",
                        "1",
                        CloseReason.LIQUIDATION,
                        "230000",
                        FUTURES,
                        BRACKETS))
                .withMessageContaining("outside the brackets");
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
                        LiquidityRole.TAKER,
                        BigDecimal.ONE,
                        BigDecimal.ZERO,
                        CloseReason.MANUAL,
                        BigDecimal.ONE,
                        SPOT,
                        List.of()));
    }

    @Test
    void BR35_priceAtOrBelowZero_isRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> calculate(
                        MarketType.SPOT,
                        Direction.LONG,
                        LiquidityRole.TAKER,
                        "0",
                        "1",
                        CloseReason.MANUAL,
                        "1",
                        SPOT,
                        List.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> calculate(
                        MarketType.SPOT,
                        Direction.LONG,
                        LiquidityRole.TAKER,
                        "1",
                        "1",
                        CloseReason.MANUAL,
                        "-1",
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
                LiquidityRole.TAKER,
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
            LiquidityRole entryLiquidity,
            String entry,
            String qty,
            CloseReason reason,
            String exit,
            FeeSchedule fees,
            List<LeverageBracket> brackets) {
        // A Futures trade with no settlement while it was open: its funding is a complete 0.
        FundingFee funding = market == MarketType.SPOT ? null : FundingFee.complete(Rounding.toAmount(BigDecimal.ZERO));
        return calculate(market, direction, entryLiquidity, entry, qty, reason, exit, fees, brackets, funding);
    }

    private static TradeResult calculate(
            MarketType market,
            Direction direction,
            LiquidityRole entryLiquidity,
            String entry,
            String qty,
            CloseReason reason,
            String exit,
            FeeSchedule fees,
            List<LeverageBracket> brackets,
            FundingFee funding) {
        return TradeResultCalculator.calculate(
                new TradeClose(
                        market,
                        direction,
                        entryLiquidity,
                        new BigDecimal(entry),
                        new BigDecimal(qty),
                        reason,
                        new BigDecimal(exit),
                        fees,
                        brackets),
                funding);
    }

    /** The liquidation price of qty 1 at this entry, from the same calculation a plan uses (tick 0.01). */
    private static LiquidationEstimate liquidation(Direction direction, String entry, int leverage) {
        BigDecimal price = new BigDecimal(entry);
        BigDecimal stop = price.multiply(new BigDecimal(direction == Direction.LONG ? "0.99" : "1.01"));
        return (LiquidationEstimate) LiquidationCalculator.calculate(new LiquidationInput(
                direction,
                price,
                stop,
                BigDecimal.ONE,
                leverage,
                new BigDecimal("1000000"),
                new BigDecimal("0.01"),
                BRACKETS));
    }

    private static LeverageBracket bracketOf(List<LeverageBracket> brackets, BigDecimal notional) {
        return LiquidationCalculator.positionBracket(brackets, BigDecimal.ONE, notional)
                .orElseThrow();
    }

    private static void assertAmounts(TradeResult r, String realized, String fee, String net) {
        assertThat(r.realizedPnl()).isEqualByComparingTo(realized);
        assertThat(r.feeAmount()).isEqualByComparingTo(fee);
        assertThat(r.netPnl().amount()).isEqualByComparingTo(net);
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
