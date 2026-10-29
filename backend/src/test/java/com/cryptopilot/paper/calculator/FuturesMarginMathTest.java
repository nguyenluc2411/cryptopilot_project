package com.cryptopilot.paper.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.cryptopilot.market.LeverageTier;
import com.cryptopilot.paper.model.IsolatedCashFlow;
import com.cryptopilot.paper.model.PositionFill;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.PositionDirection;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The refusals and edge cases of {@link FuturesMarginMath} that the shared golden cases do not name.
 *
 * <p>Rule: TR-40; D-127.
 */
class FuturesMarginMathTest {

    private static final List<LeverageTier> TIERS =
            List.of(tier(2, "50000", "600000", 100, "0.005", null), tier(1, "0", "50000", 125, "0.004", null));

    @Test
    void TR40_bracketsInAnyOrder_areSortedAndGetTheirMaintenanceAmounts() {
        List<LeverageTier> resolved = FuturesMarginMath.withMaintenanceAmounts(TIERS);

        assertThat(resolved).extracting(LeverageTier::bracketNo).containsExactly(1, 2);
        assertThat(resolved.get(1).maintenanceAmount()).isEqualByComparingTo("50");
        assertThat(FuturesMarginMath.maxLeverage(TIERS)).isEqualTo(125);
    }

    /**
     * TR-14b (plan 4.5 note 2): a hedged LONG and SHORT in cross share one price,
     * {@code (WB − QL × EL + QS × ES) / (QL × MMR + QS × MMR − QL + QS)}, rounded against the larger side.
     */
    @Test
    void TR14b_aHedgedPairInCross_sharesOneLiquidationPrice() {
        BigDecimal tick = new BigDecimal("0.1");
        // (10 − 100 + 50) / (0.004 + 0.002 − 1 + 0.5) = 80.97..., the long larger: up.
        assertThat(hedge("1", "0.5", "10", TIERS, tick)).isEqualByComparingTo("81.0");
        // (10 − 50 + 100) / (0.002 + 0.004 − 0.5 + 1) = 118.57..., the short larger: down.
        assertThat(hedge("0.5", "1", "10", TIERS, tick)).isEqualByComparingTo("118.5");
        // A wallet that covers any fall: no price liquidates.
        assertThat(hedge("1", "0.5", "100", TIERS, tick)).isZero();
        // Fully hedged with no maintenance: nothing to solve.
        assertThat(hedge("1", "1", "10", List.of(tier(1, "0", "50000", 125, "0", "0")), tick))
                .isZero();
        // A side large enough for the second bracket is solved again with it.
        assertThat(hedge("600", "1", "1000", TIERS, tick)).isPositive();
        assertThatIllegalArgumentException().isThrownBy(() -> hedge("1", "1", "10", List.of(), tick));
        assertThatIllegalArgumentException().isThrownBy(() -> hedge("0", "1", "10", TIERS, tick));
    }

    private static BigDecimal hedge(
            String longQuantity, String shortQuantity, String wallet, List<LeverageTier> tiers, BigDecimal tick) {
        return FuturesMarginMath.hedgeLiquidationPrice(
                new BigDecimal(longQuantity),
                new BigDecimal("100"),
                new BigDecimal(shortQuantity),
                new BigDecimal("100"),
                new BigDecimal(wallet),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                tiers,
                tick);
    }

    @Test
    void TR40_aPairWithoutBrackets_isRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> FuturesMarginMath.maxLeverage(List.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FuturesMarginMath.liquidationPrice(
                        PositionDirection.LONG,
                        BigDecimal.ONE,
                        new BigDecimal("100"),
                        BigDecimal.TEN,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        List.of(),
                        new BigDecimal("0.01")));
        assertThat(FuturesMarginMath.maxNotionalAtLeverage(List.of(), 10)).isZero();
    }

    @Test
    void TR40_aLeverageBelowOne_isRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> FuturesMarginMath.initialMargin(BigDecimal.TEN, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FuturesMarginMath.orderCost(
                        OrderSide.BUY, BigDecimal.ONE, null, BigDecimal.TEN, BigDecimal.TEN, 0));
    }

    @Test
    void TR40_aFillWithoutQuantityOrPrice_isRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FuturesMarginMath.applyFill(
                        BigDecimal.ZERO, null, OrderSide.BUY, BigDecimal.ZERO, BigDecimal.TEN));
        assertThatNullPointerException()
                .isThrownBy(
                        () -> FuturesMarginMath.applyFill(BigDecimal.ZERO, null, OrderSide.BUY, BigDecimal.ONE, null));
    }

    @Test
    void TR40_closingMoreThanThePosition_isRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FuturesMarginMath.closeFlow(
                        new BigDecimal("600"),
                        new BigDecimal("0.2"),
                        new BigDecimal("0.1"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO));
    }

    @Test
    void TR16_onlyAFillLargerThanTheOppositePosition_reverses() {
        PositionFill reversal = FuturesMarginMath.applyFill(
                new BigDecimal("-0.1"),
                new BigDecimal("60000"),
                OrderSide.BUY,
                new BigDecimal("0.3"),
                new BigDecimal("59000"));
        PositionFill reduction = FuturesMarginMath.applyFill(
                new BigDecimal("-0.1"),
                new BigDecimal("60000"),
                OrderSide.BUY,
                new BigDecimal("0.05"),
                new BigDecimal("59000"));

        assertThat(reversal.reverses()).isTrue();
        assertThat(reversal.realizedPnl()).isEqualByComparingTo("100");
        assertThat(reversal.quantityAfter()).isEqualByComparingTo("0.2");
        assertThat(reduction.reverses()).isFalse();
        assertThat(reduction.quantityAfter()).isEqualByComparingTo("-0.05");
    }

    @Test
    void TR16_everyBranch_keepsCrossEqualToWalletMinusIsolated() {
        IsolatedCashFlow insured = FuturesMarginMath.closeFlow(
                new BigDecimal("600"),
                new BigDecimal("0.1"),
                new BigDecimal("0.1"),
                new BigDecimal("-650"),
                new BigDecimal("5"));

        assertThat(insured.branch()).isEqualTo(IsolatedCashFlow.Branch.INSURANCE);
        assertThat(insured.insuranceCover()).isEqualByComparingTo("55");
        // The wallet loses the realized loss and the fee, gets the cover: −650 − 5 + 55 = −600.
        assertThat(insured.walletChange()).isEqualByComparingTo("-600");
    }

    @Test
    void TR22_aShortCannotBeLiquidatedBelowZero_andALongWithEnoughMarginHasNoPrice() {
        BigDecimal shortPrice = FuturesMarginMath.liquidationPrice(
                PositionDirection.SHORT,
                new BigDecimal("0.1"),
                new BigDecimal("60000"),
                new BigDecimal("600"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                TIERS,
                new BigDecimal("0.01"));
        BigDecimal longPrice = FuturesMarginMath.liquidationPrice(
                PositionDirection.LONG,
                new BigDecimal("0.1"),
                new BigDecimal("60000"),
                new BigDecimal("6000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                TIERS,
                new BigDecimal("0.01"));

        // (600 + 6,000) / (0.1 × 1.004) = 65,737.05… rounded down.
        assertThat(shortPrice).isEqualByComparingTo("65737.05");
        assertThat(longPrice).isZero();
    }

    @Test
    void TR40_aNotionalAboveEveryBracket_isLiquidatedWithTheLargestBracket() {
        BigDecimal price = FuturesMarginMath.liquidationPrice(
                PositionDirection.LONG,
                new BigDecimal("20"),
                new BigDecimal("60000"),
                new BigDecimal("120000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                TIERS,
                new BigDecimal("0.01"));

        assertThat(price).isPositive().isLessThan(new BigDecimal("60000"));
    }

    private static LeverageTier tier(int no, String floor, String cap, int leverage, String mmr, String cum) {
        return new LeverageTier(
                no,
                new BigDecimal(floor),
                new BigDecimal(cap),
                leverage,
                new BigDecimal(mmr),
                cum == null ? null : new BigDecimal(cum));
    }
}
