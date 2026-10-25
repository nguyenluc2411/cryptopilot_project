package com.cryptopilot.trading.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.ClosedTradeFunding;
import com.cryptopilot.trading.OpenTrade;
import com.cryptopilot.trading.RecordedPosition;
import com.cryptopilot.trading.SimulatedTradeClosing;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.PositionStatus;
import com.cryptopilot.trading.model.enums.PriceSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The records of the exits: the mark price range, the position the engine tracks, the exit outcome, and what the
 * journal stores and answers.
 *
 * <p>Rule: NSF-07, BR-35, BR-37, BR-38; D-97; D-119.
 */
class ExitRecordsTest {

    private static final UUID PAIR = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-10-03T08:00:00Z");

    @Test
    void NSF07_aMarkRange_isAFuturesRangeOfMarkPrices_andMergesOnlyWithMarkPrices() {
        PriceRange marks = PriceRange.mark(PAIR, BigDecimal.ONE, BigDecimal.TEN, AT, AT.plusSeconds(1), false);
        PriceRange last = new PriceRange(MarketType.FUTURES, PAIR, BigDecimal.ONE, BigDecimal.TEN, AT);

        assertThat(marks.source()).isEqualTo(PriceSource.MARK);
        assertThat(marks.market()).isEqualTo(MarketType.FUTURES);
        assertThat(last.source()).isEqualTo(PriceSource.LAST);
        assertThat(marks.mergedWith(PriceRange.mark(PAIR, BigDecimal.ZERO, BigDecimal.ONE, AT, AT.plusSeconds(2), true))
                        .source())
                .isEqualTo(PriceSource.MARK);
        assertThatIllegalArgumentException().isThrownBy(() -> marks.mergedWith(last));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PriceRange(
                        MarketType.SPOT, PAIR, null, BigDecimal.ONE, BigDecimal.TEN, AT, AT, false, PriceSource.MARK))
                .withMessageContaining("Futures");
        assertThatNullPointerException()
                .isThrownBy(() -> new PriceRange(
                        MarketType.FUTURES, PAIR, null, BigDecimal.ONE, BigDecimal.TEN, AT, AT, false, null));
    }

    @Test
    void NSF07_aTrackedPosition_needsItsIdentityAndEntry_andAnswersItsLevels() {
        TrackedPosition position = position();

        assertThat(position.levels())
                .isEqualTo(new OpenPosition(
                        position.planId(),
                        Direction.LONG,
                        new BigDecimal("100"),
                        AT,
                        new BigDecimal("115"),
                        new BigDecimal("95"),
                        new BigDecimal("90")));
        assertThatNullPointerException()
                .isThrownBy(() -> new TrackedPosition(
                        null,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        MarketType.SPOT,
                        PAIR,
                        Direction.LONG,
                        BigDecimal.ONE,
                        AT,
                        null,
                        null,
                        null,
                        0));
        assertThatNullPointerException()
                .isThrownBy(() ->
                        new OpenPosition(UUID.randomUUID(), Direction.LONG, BigDecimal.ONE, null, null, null, null));
    }

    @Test
    void NSF07_anExitOutcome_isOfThePositionsPlan_atTheExitTime() {
        TrackedPosition position = position();
        PositionExit exit = new PositionExit(
                position,
                new Exit(position.planId(), CloseReason.TAKE_PROFIT, new BigDecimal("115"), AT),
                Excursion.at(BigDecimal.ONE));

        assertThat(exit.planId()).isEqualTo(position.planId());
        assertThat(exit.at()).isEqualTo(AT);
        assertThatNullPointerException()
                .isThrownBy(() -> new PositionExit(position, null, Excursion.at(BigDecimal.ONE)));
        assertThatNullPointerException().isThrownBy(() -> new Excursion(null, BigDecimal.ONE));
    }

    @Test
    void D97_aClosingWithoutFunding_cannotBeIncomplete_andCopiesItsMissingTimes() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> closing(null, true, List.of()))
                .withMessageContaining("incomplete");
        List<Instant> missing = new ArrayList<>(List.of(AT));
        SimulatedTradeClosing closing = closing(BigDecimal.ONE, true, missing);
        missing.clear();
        assertThat(closing.missingFundingTimes()).containsExactly(AT);
        assertThat(new ClosedTradeFunding(BigDecimal.ONE, true, missing).missingFundingTimes())
                .isEmpty();
    }

    @Test
    void BR38_aClosedPosition_answersItsAmounts_andItsNetByTheOneFormula() {
        RecordedPosition closed = new RecordedPosition(
                UUID.randomUUID(),
                PositionStatus.CLOSED,
                new BigDecimal("100"),
                new BigDecimal("2"),
                AT,
                new BigDecimal("0.04"),
                null,
                new BigDecimal("115"),
                AT.plusSeconds(3600),
                CloseReason.TAKE_PROFIT,
                new BigDecimal("30"),
                new BigDecimal("0.086"),
                new BigDecimal("-0.0202"),
                true);

        assertThat(closed.amounts().netPnl()).isEqualTo(new NetPnl(new BigDecimal("29.8938"), true));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RecordedPosition(
                        UUID.randomUUID(),
                        PositionStatus.CLOSED,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        AT,
                        BigDecimal.ZERO,
                        null,
                        null,
                        null,
                        null,
                        null,
                        BigDecimal.ONE,
                        null,
                        false));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RecordedPosition(
                        UUID.randomUUID(),
                        PositionStatus.CLOSED,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        AT,
                        BigDecimal.ZERO,
                        null,
                        null,
                        null,
                        null,
                        BigDecimal.ONE,
                        null,
                        null,
                        false));
    }

    @Test
    void D119_anOpenTrade_needsItsEntry() {
        assertThatNullPointerException()
                .isThrownBy(() -> new OpenTrade(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        PAIR,
                        MarketType.SPOT,
                        Direction.LONG,
                        BigDecimal.ONE,
                        AT,
                        BigDecimal.ONE,
                        null,
                        BigDecimal.ZERO,
                        null,
                        null,
                        null,
                        0));
    }

    private static TrackedPosition position() {
        return new TrackedPosition(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                MarketType.FUTURES,
                PAIR,
                Direction.LONG,
                new BigDecimal("100"),
                AT,
                new BigDecimal("95"),
                new BigDecimal("115"),
                new BigDecimal("90"),
                0);
    }

    private static SimulatedTradeClosing closing(BigDecimal funding, boolean incomplete, List<Instant> missing) {
        return new SimulatedTradeClosing(
                UUID.randomUUID(),
                0,
                BigDecimal.ONE,
                AT,
                CloseReason.STOP_LOSS,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                funding,
                incomplete,
                missing);
    }
}
