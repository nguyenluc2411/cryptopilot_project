package com.cryptopilot.trading.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.trading.calculator.warning.PlanFixture;
import com.cryptopilot.trading.calculator.warning.WarningEvaluator;
import com.cryptopilot.trading.model.PlanCalculation;
import com.cryptopilot.trading.model.PlanCosts;
import com.cryptopilot.trading.model.PlanSnapshot;
import com.cryptopilot.trading.model.PlanWarning;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.WarningSeverity;
import com.cryptopilot.trading.model.enums.WarningType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The fee-aware risk panel: the expected fees and slippage of a sized plan, what they do to its loss at the stop loss
 * and its risk/reward, and HIGH_FEE_SHARE. The quantity is still sized on |entry − stop loss| (BR-23). Vectors of the
 * team lead's decision (2026-10-23) at the seeded rates: Futures maker 0.02 %, taker 0.05 %; Spot 0.1 %.
 *
 * <p>Rule: BR-23, BR-29, BR-31, BR-36; D-111 E1, E5; D-112 R6 and its note; D-116; TECHNICAL_DESIGN 7.5.
 */
class PlanCostCalculatorTest {

    /**
     * Futures LONG MARKET, entry 100,000, SL 99,500, TP 101,500, risk 100 USDT, no slippage: quantity 0.2; entry fee
     * 0.05 % of 20,000 = 10; SL exit fee 0.05 % of 19,900 = 9.95; loss at SL 119.95, a share of 0.1995, so no
     * warning; TP exit fee 0.02 % of 20,300 = 4.06; R:R after fees 285.94 / 119.95.
     */
    @Test
    void D116_futuresLongMarket_isTheDecisionsVector() {
        PlanCalculation plan = futuresMarket("99500").build();

        PlanCosts costs = plan.costs();
        assertThat(plan.sized().quantity()).isEqualByComparingTo("0.2");
        assertThat(plan.sized().riskAmount()).isEqualByComparingTo("100");
        assertThat(costs.estimatedEntryFee()).isEqualByComparingTo("10");
        assertThat(costs.estimatedStopLossExitFee()).isEqualByComparingTo("9.95");
        assertThat(costs.estimatedTakeProfitExitFee()).isEqualByComparingTo("4.06");
        assertThat(costs.estimatedSlippage()).isEqualByComparingTo("0");
        assertThat(costs.lossAtStopLoss()).isEqualByComparingTo("119.95");
        assertThat(costs.feeShareOfRisk()).isEqualByComparingTo("0.1995");
        assertThat(costs.netRiskReward()).isEqualByComparingTo(ratio("285.94", "119.95"));
        assertThat(types(plan)).doesNotContain(WarningType.HIGH_FEE_SHARE);
        assertThat(PlanSnapshot.of(plan).costs().netRiskReward()).isEqualTo(new BigDecimal("2.38382659"));
    }

    /**
     * The same plan with entry 100,250 and a tighter stop: the share is 0.0005 × (2 × entry − d) / d for a stop
     * distance d, whatever the quantity. d = 500 gives exactly 0.2, which warns (at the threshold); d = 499.99 gives
     * 0.2000040…, which warns; d = 500.01 gives 0.1999959…, which does not.
     */
    @ParameterizedTest(name = "SL {0}: share {1}, warns {2}")
    @CsvSource({
        "99750,    0.2,                 true,  20",
        "99750.01, 0.2000040100802016,  true,  20.0004",
        "99749.99, 0.1999959900801984,  false, "
    })
    void D116_highFeeShare_isRaisedAtAndAboveTheRatio_andNotBelow(
            String stop, String share, boolean warns, String shownPercent) {
        PlanCalculation plan = futuresMarket(stop)
                .entry("100250")
                .lastPrice("100250")
                .takeProfit("101750")
                .build();

        assertThat(plan.costs().feeShareOfRisk()).isCloseTo(new BigDecimal(share), within("0.0000000000000001"));
        List<PlanWarning> warnings = WarningEvaluator.evaluate(plan);
        assertThat(types(plan).contains(WarningType.HIGH_FEE_SHARE)).isEqualTo(warns);
        if (warns) {
            PlanWarning warning = warnings.stream()
                    .filter(w -> w.type() == WarningType.HIGH_FEE_SHARE)
                    .findFirst()
                    .orElseThrow();
            assertThat(warning.severity()).isEqualTo(WarningSeverity.WARNING);
            assertThat(warning.message()).isEqualTo("fees and slippage are " + shownPercent + "% of your risk");
            assertThat(WarningEvaluator.blocksActivation(warnings)).isFalse();
        }
    }

    /** The ratio is a setting (FEE_RISK_WARN_RATIO): at 0.1995 the decision's vector warns too. */
    @Test
    void D116_theRatio_isTheSettingPassedIn() {
        assertThat(types(futuresMarket("99500").feeRiskWarnRatio("0.1995").build()))
                .contains(WarningType.HIGH_FEE_SHARE);
        assertThat(types(futuresMarket("99500").feeRiskWarnRatio("0.19950001").build()))
                .doesNotContain(WarningType.HIGH_FEE_SHARE);
    }

    /**
     * Spot LONG resting LIMIT, entry 100, SL 98, TP 106, risk 100: quantity 50; maker entry fee 5; SL exit 4.9; loss
     * at SL 109.9; TP exit 5.3; R:R after fees 289.7 / 109.9.
     */
    @Test
    void D116_spotLongRestingLimit_isTheDecisionsVector() {
        PlanCalculation plan = spotLimit().build();

        PlanCosts costs = plan.costs();
        assertThat(plan.sized().quantity()).isEqualByComparingTo("50");
        assertThat(costs.estimatedEntryFee()).isEqualByComparingTo("5");
        assertThat(costs.estimatedStopLossExitFee()).isEqualByComparingTo("4.9");
        assertThat(costs.estimatedTakeProfitExitFee()).isEqualByComparingTo("5.3");
        assertThat(costs.lossAtStopLoss()).isEqualByComparingTo("109.9");
        assertThat(costs.feeShareOfRisk()).isEqualByComparingTo("0.099");
        assertThat(costs.netRiskReward()).isEqualByComparingTo(ratio("289.7", "109.9"));
    }

    /**
     * 10 bps on the same Spot plan: the maker legs do not move (entry fee 5, TP exit fee 5.3, no entry slippage); the
     * stop loss sells at 98 × 0.999 = 97.902, rounded down to 97.90, so 0.10 × 50 = 5 of slippage; loss at SL 114.9;
     * R:R after fees 289.7 / 114.9.
     */
    @Test
    void D116_slippage_movesTheTakerLegs_andLeavesTheMakerLegs() {
        PlanCalculation plan = spotLimit().slippageBps(10).build();

        PlanCosts costs = plan.costs();
        assertThat(costs.estimatedEntryFee()).isEqualByComparingTo("5");
        assertThat(costs.estimatedTakeProfitExitFee()).isEqualByComparingTo("5.3");
        assertThat(costs.estimatedStopLossExitFee()).isEqualByComparingTo("4.9");
        assertThat(costs.estimatedSlippage()).isEqualByComparingTo("5");
        assertThat(costs.lossAtStopLoss()).isEqualByComparingTo("114.9");
        assertThat(costs.netRiskReward()).isEqualByComparingTo(ratio("289.7", "114.9"));
    }

    /**
     * Futures LONG MARKET, last price 100, 10 bps (TD 7.8): the entry is sized on the price paid, 100.10 (D-112 R6),
     * so quantity 10.2 / 5.10 = 2, risk 10.2 and reward 19.8 already hold the entry slippage of 0.2, which the loss
     * does not count again. Entry fee 0.1001 (taker), SL fee 0.095, TP fee 0.044 (maker); SL sells at 94.90, 0.2 of
     * slippage; estimated slippage 0.4; loss at SL 10.2 + 0.1001 + 0.095 + 0.2 = 10.5951; R:R after fees
     * (19.8 − 0.1001 − 0.044) / 10.5951.
     */
    @Test
    void D116_aMarketEntrysSlippage_isInTheSizedRisk_andCountedOnce() {
        PlanCalculation plan = PlanFixture.futuresLong()
                .entryType(EntryType.MARKET)
                .lastPrice("100")
                .entry("100.10")
                .capital("1020")
                .slippageBps(10)
                .build();

        PlanCosts costs = plan.costs();
        assertThat(plan.sized().quantity()).isEqualByComparingTo("2");
        assertThat(plan.sized().riskAmount()).isEqualByComparingTo("10.2");
        assertThat(costs.estimatedEntryFee()).isEqualByComparingTo("0.1001");
        assertThat(costs.estimatedStopLossExitFee()).isEqualByComparingTo("0.095");
        assertThat(costs.estimatedTakeProfitExitFee()).isEqualByComparingTo("0.044");
        assertThat(costs.estimatedSlippage()).isEqualByComparingTo("0.4");
        assertThat(costs.lossAtStopLoss()).isEqualByComparingTo("10.5951");
        assertThat(costs.netRiskReward()).isEqualByComparingTo(ratio("19.6559", "10.5951"));
    }

    /**
     * Futures LONG LIMIT 100 the last price 99.95 already reaches, 10 bps: a taker entry that slips to 100.05 and is
     * capped at its limit, 100, so 0.05 × 2 = 0.1 of entry slippage. It is sized on the limit (risk 10, reward 20), so
     * the entry slippage is added: loss at SL 10 + 0.1 (taker entry fee) + 0.095 + 0.2 + 0.1 = 10.495; R:R after fees
     * (20 − 0.1 − 0.044 − 0.1) / 10.495.
     */
    @Test
    void D116_aMarketableLimitsCappedSlippage_isAddedToTheLoss() {
        PlanCalculation plan =
                PlanFixture.futuresLong().lastPrice("99.95").slippageBps(10).build();

        PlanCosts costs = plan.costs();
        assertThat(costs.estimatedEntryFee()).isEqualByComparingTo("0.1");
        assertThat(costs.estimatedSlippage()).isEqualByComparingTo("0.3");
        assertThat(costs.lossAtStopLoss()).isEqualByComparingTo("10.495");
        assertThat(costs.netRiskReward()).isEqualByComparingTo(ratio("19.756", "10.495"));
    }

    /** A LIMIT the last price does not reach rests and pays maker; one it reaches pays taker (D-111 E1). */
    @ParameterizedTest(name = "last {0}: entry fee {1}")
    @CsvSource({"100.01, 0.04", "100.00, 0.1"})
    void D111_aLimitsEntryFee_followsWhetherTheLastPriceReachesIt(String last, String entryFee) {
        assertThat(PlanFixture.futuresLong().lastPrice(last).build().costs().estimatedEntryFee())
                .isEqualByComparingTo(entryFee);
    }

    /**
     * SHORT STOP 95, SL 100, TP 85, 10 bps: a stop-market entry is taker and sells at 95 × 0.999 = 94.905 → 94.90, so
     * 0.10 per unit of entry slippage; the SL buys at 100 × 1.001 = 100.10, so 0.10 per unit too.
     */
    @Test
    void D116_aStopEntry_isTakerAndSlipsFromItsStopPrice() {
        PlanCalculation plan = PlanFixture.futuresShort()
                .entryType(EntryType.STOP)
                .entry("95")
                .stop("100")
                .takeProfit("85")
                .slippageBps(10)
                .build();

        BigDecimal quantity = plan.sized().quantity();
        assertThat(plan.costs().estimatedEntryFee())
                .isEqualByComparingTo(quantity.multiply(new BigDecimal("95")).multiply(new BigDecimal("0.0005")));
        assertThat(plan.costs().estimatedSlippage()).isEqualByComparingTo(quantity.multiply(new BigDecimal("0.20")));
    }

    private static PlanFixture futuresMarket(String stop) {
        return PlanFixture.futuresLong()
                .entryType(EntryType.MARKET)
                .direction(Direction.LONG)
                .entry("100000")
                .lastPrice("100000")
                .stop(stop)
                .takeProfit("101500")
                .capital("10000");
    }

    private static PlanFixture spotLimit() {
        return PlanFixture.spot().entry("100").stop("98").takeProfit("106").capital("10000");
    }

    private static List<WarningType> types(PlanCalculation plan) {
        return WarningEvaluator.evaluate(plan).stream().map(PlanWarning::type).toList();
    }

    private static BigDecimal ratio(String dividend, String divisor) {
        return Rounding.divide(new BigDecimal(dividend), new BigDecimal(divisor));
    }

    private static org.assertj.core.data.Offset<BigDecimal> within(String offset) {
        return org.assertj.core.data.Offset.offset(new BigDecimal(offset));
    }
}
