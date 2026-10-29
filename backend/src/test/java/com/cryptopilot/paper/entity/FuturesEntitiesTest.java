package com.cryptopilot.paper.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.cryptopilot.paper.model.enums.MarginAdjustmentRefType;
import com.cryptopilot.paper.model.enums.MarginAdjustmentType;
import com.cryptopilot.paper.model.enums.MarginType;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.PositionCloseReason;
import com.cryptopilot.paper.model.enums.PositionDirection;
import com.cryptopilot.paper.model.enums.PositionSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The paper Futures entities' own rules: defaults, the isolated wallet that never goes below zero and exists only on
 * an isolated position, and the adjustment that records a change with the wallet after it.
 *
 * <p>Rule: TR-14, TR-16; D-127.
 */
class FuturesEntitiesTest {

    private static final UUID TRADER = UUID.randomUUID();
    private static final UUID PAIR = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");
    private static final PositionCloseReason REASON = PositionCloseReason.MANUAL;

    @Test
    void TR14_theAccountDefaults_areOneWayAndNotLiquidating() {
        FuturesAccountConfig config = FuturesAccountConfig.defaults(TRADER);

        assertThat(config.userId()).isEqualTo(TRADER);
        assertThat(config.isDualSidePosition()).isFalse();
        assertThat(config.liquidating()).isFalse();
    }

    @Test
    void TR14_aPairsDefaults_are20xCrossed_orItsHighestLeverageWhenLower() {
        assertThat(FuturesSymbolConfig.defaults(TRADER, PAIR, 125).getLeverage())
                .isEqualTo(20);
        assertThat(FuturesSymbolConfig.defaults(TRADER, PAIR, 10).getLeverage()).isEqualTo(10);
        assertThat(FuturesSymbolConfig.defaults(TRADER, PAIR, 125).getMarginType())
                .isEqualTo(MarginType.CROSSED);
    }

    @Test
    void TR14_aPairsSettings_change_butNeverToALeverageBelowOne() {
        FuturesSymbolConfig config = FuturesSymbolConfig.defaults(TRADER, PAIR, 125);

        config.changeLeverage(50);
        config.changeMarginType(MarginType.ISOLATED);

        assertThat(config.getLeverage()).isEqualTo(50);
        assertThat(config.getMarginType()).isEqualTo(MarginType.ISOLATED);
        assertThatIllegalArgumentException().isThrownBy(() -> config.changeLeverage(0));
    }

    @Test
    void TR16_anIsolatedPosition_startsWithAnEmptyWallet_thatNeverGoesBelowZero() {
        FuturesPosition position = isolatedLong();

        position.adjustIsolatedWallet(new BigDecimal("600"));
        position.adjustIsolatedWallet(new BigDecimal("-100"));

        assertThat(position.getIsolatedWalletAmount()).isEqualByComparingTo("500");
        assertThat(position.isOpen()).isTrue();
        assertThat(position.signedQuantity()).isEqualByComparingTo("0.1");
        assertThat(position.getMaxQuantity()).isEqualByComparingTo("0.1");
        assertThatIllegalStateException().isThrownBy(() -> position.adjustIsolatedWallet(new BigDecimal("-500.01")));
    }

    @Test
    void TR16_aCrossPosition_hasNoIsolatedWallet() {
        FuturesPosition position = FuturesPosition.open(
                TRADER,
                PAIR,
                PositionSide.BOTH,
                PositionDirection.SHORT,
                MarginType.CROSSED,
                20,
                new BigDecimal("2"),
                new BigDecimal("3000"),
                NOW);

        assertThat(position.getIsolatedWalletAmount()).isNull();
        assertThat(position.signedQuantity()).isEqualByComparingTo("-2");
        assertThatIllegalStateException().isThrownBy(() -> position.adjustIsolatedWallet(BigDecimal.ONE));
    }

    @Test
    void TR16_aPosition_needsASizeAndAnEntry_andALeverageOfAtLeastOne() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FuturesPosition.open(
                        TRADER,
                        PAIR,
                        PositionSide.BOTH,
                        PositionDirection.LONG,
                        MarginType.CROSSED,
                        20,
                        BigDecimal.ZERO,
                        new BigDecimal("3000"),
                        NOW));
        FuturesPosition position = isolatedLong();
        position.changeLeverage(25);
        assertThat(position.getLeverage()).isEqualTo(25);
        assertThatIllegalArgumentException().isThrownBy(() -> position.changeLeverage(0));
    }

    @Test
    void TR16_anAdjustment_recordsTheChangeAndTheWalletAfterIt() {
        FuturesPosition position = isolatedLong();
        position.adjustIsolatedWallet(new BigDecimal("600"));
        UUID fill = UUID.randomUUID();

        FuturesMarginAdjustment adjustment = FuturesMarginAdjustment.of(
                position, MarginAdjustmentType.OPEN, new BigDecimal("600"), MarginAdjustmentRefType.FILL, fill);

        assertThat(adjustment.getPositionId()).isEqualTo(position.getId());
        assertThat(adjustment.getUserId()).isEqualTo(TRADER);
        assertThat(adjustment.getPairId()).isEqualTo(PAIR);
        assertThat(adjustment.getIwAfterAmount()).isEqualByComparingTo("600");
        assertThat(adjustment.getRefId()).isEqualTo(fill);
    }

    @Test
    void TR16_anAdjustment_isNeverZero_andNamesBothPartsOfItsReference() {
        FuturesPosition position = isolatedLong();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> FuturesMarginAdjustment.of(
                        position, MarginAdjustmentType.MANUAL_ADD, BigDecimal.ZERO, null, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FuturesMarginAdjustment.of(
                        position,
                        MarginAdjustmentType.MANUAL_ADD,
                        BigDecimal.ONE,
                        MarginAdjustmentRefType.LEDGER,
                        null));
    }

    @Test
    void TR16_aSide_opensItsDirection() {
        assertThat(PositionDirection.openedBy(OrderSide.BUY)).isEqualTo(PositionDirection.LONG);
        assertThat(PositionDirection.openedBy(OrderSide.SELL)).isEqualTo(PositionDirection.SHORT);
        assertThat(PositionDirection.SHORT.sign()).isEqualByComparingTo("-1");
    }

    private static FuturesPosition isolatedLong() {
        return FuturesPosition.open(
                TRADER,
                PAIR,
                PositionSide.BOTH,
                PositionDirection.LONG,
                MarginType.ISOLATED,
                10,
                new BigDecimal("0.1"),
                new BigDecimal("60000"),
                NOW);
    }

    @Test
    void TR16_aFillThatIncreases_averagesTheEntry_andKeepsTheLargestSize() {
        FuturesPosition position = cross("0.1", "60000");

        position.increase(new BigDecimal("0.05"), new BigDecimal("61000"));

        assertThat(position.getQuantity()).isEqualByComparingTo("0.15");
        assertThat(position.getMaxQuantity()).isEqualByComparingTo("0.15");
        assertThat(position.getEntryPrice()).isEqualByComparingTo("61000");
    }

    @Test
    void TR16_aFillThatReduces_keepsTheEntry_andClosesThePositionWhenNothingIsLeft() {
        FuturesPosition position = cross("0.2", "60000");

        position.reduce(new BigDecimal("0.1"), new BigDecimal("62000"), new BigDecimal("200"), REASON, NOW);
        position.reduce(new BigDecimal("0.1"), new BigDecimal("64000"), new BigDecimal("400"), REASON, NOW);

        assertThat(position.isOpen()).isFalse();
        assertThat(position.getQuantity()).isEqualByComparingTo("0");
        assertThat(position.getMaxQuantity()).isEqualByComparingTo("0.2");
        assertThat(position.getEntryPrice()).isEqualByComparingTo("60000");
        assertThat(position.getClosedQuantity()).isEqualByComparingTo("0.2");
        assertThat(position.getCloseAvgPrice()).isEqualByComparingTo("63000");
        assertThat(position.getRealizedPnlAmount()).isEqualByComparingTo("600");
        assertThat(position.getCloseReason()).isEqualTo(REASON);
        assertThat(position.getClosedAt()).isEqualTo(NOW);
        assertThatIllegalStateException().isThrownBy(() -> position.increase(BigDecimal.ONE, BigDecimal.ONE));
    }

    @Test
    void TR16_aPosition_isNeverReducedPastItsSize_norClosedWithMarginLeftInIt() {
        FuturesPosition cross = cross("0.1", "60000");
        FuturesPosition isolated = FuturesPosition.open(
                TRADER,
                PAIR,
                PositionSide.BOTH,
                PositionDirection.SHORT,
                MarginType.ISOLATED,
                10,
                new BigDecimal("0.1"),
                new BigDecimal("60000"),
                NOW);
        isolated.adjustIsolatedWallet(new BigDecimal("600"));

        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        cross.reduce(new BigDecimal("0.2"), new BigDecimal("60000"), BigDecimal.ZERO, REASON, NOW));
        assertThatIllegalStateException()
                .isThrownBy(() ->
                        isolated.reduce(new BigDecimal("0.1"), new BigDecimal("60000"), BigDecimal.ZERO, REASON, NOW));
    }

    private static FuturesPosition cross(String quantity, String entry) {
        return FuturesPosition.open(
                TRADER,
                PAIR,
                PositionSide.BOTH,
                PositionDirection.LONG,
                MarginType.CROSSED,
                20,
                new BigDecimal(quantity),
                new BigDecimal(entry),
                NOW);
    }
}
