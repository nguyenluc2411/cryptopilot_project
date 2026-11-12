package com.cryptopilot.trading.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.market.ClosedCandleIndicators;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.SetupScoreReading;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.model.enums.SetupStyle;
import com.cryptopilot.trading.SimulatedFillLearning;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.model.PlanSnapshot;
import com.cryptopilot.trading.model.enums.AnalysisTimeframe;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.PlanStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The learning data a fill records: the deviation of BR-69 for each entry type, the plan's risk, both setup scores with
 * the preset stored at activation, the indicators of the latest closed candle, and the drop warning of MSG55. A read
 * that fails or finds nothing leaves a null, never an exception.
 *
 * <p>Rule: BR-69, BR-70; MSG55; D-111 (M2, M4), D-112; Q-77.
 */
class FillLearningCaptureTest {

    private static final UUID PLAN = UUID.fromString("019b76da-a800-7000-8000-0000000000d1");
    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-0000000000d2");
    private static final Instant FILLED_AT = Instant.parse("2026-11-12T08:01:00Z");
    private static final Instant CANDLE = Instant.parse("2026-11-12T07:00:00Z");

    private final MarketApi market = mock(MarketApi.class);
    private final SystemSettingApi settings = mock(SystemSettingApi.class);
    private final FillLearningCapture capture = new FillLearningCapture(market, settings);

    @BeforeEach
    void threshold() {
        when(settings.integer(FillLearningCapture.SETUP_SCORE_DROP_WARN)).thenReturn(15);
        when(market.presetTimeframe(SetupStyle.SWING)).thenReturn("4h");
    }

    /** BR-69: a resting LIMIT fills at its own price, so its deviation is 0; the plan's risk is kept. */
    @Test
    void BR70_aRestingLimitFill_hasNoDeviation_andKeepsThePlansRisk() {
        SimulatedFillLearning learning = capture.of(plan(Direction.LONG, "100", "100", "95"));

        assertThat(learning.plannedEntryPrice()).isEqualByComparingTo("100");
        assertThat(learning.entryDeviationR()).isEqualTo(new BigDecimal("0E-8"));
        assertThat(learning.riskAmount()).isEqualByComparingTo("10");
        assertThat(learning.analysisTimeframe()).isEqualTo("1h");
    }

    /** BR-69: a MARKET SHORT sold below its planned entry is a worse entry: −1 × (99 − 100) / 5 = +0.2 R. */
    @Test
    void BR70_aMarketFillBelowThePlanOfAShort_isAWorseEntry() {
        SimulatedFillLearning learning = capture.of(plan(Direction.SHORT, "100", "99", "105"));

        assertThat(learning.entryDeviationR()).isEqualByComparingTo("0.2");
    }

    /** BR-69: a LIMIT marketable at activation fills at a better price: (98 − 100) / 5 = −0.4 R. */
    @Test
    void BR70_aMarketableLimit_isABetterEntry_withANegativeDeviation() {
        SimulatedFillLearning learning = capture.of(plan(Direction.LONG, "100", "98", "95"));

        assertThat(learning.entryDeviationR()).isEqualByComparingTo("-0.4");
    }

    /** BR-69: a STOP filled through a gap at 102 instead of its trigger 100: (102 − 100) / 5 = +0.4 R. */
    @Test
    void BR70_aStopFilledThroughAGap_hasAPositiveDeviation() {
        SimulatedFillLearning learning = capture.of(plan(Direction.LONG, "100", "102", "95"));

        assertThat(learning.entryDeviationR()).isEqualByComparingTo("0.4");
    }

    /** A MARKET plan filled before its planned entry was recorded has no deviation. */
    @Test
    void BR70_withoutAPlannedEntry_thereIsNoDeviation() {
        TradingPlan plan = plan(Direction.LONG, null, "100", "95");

        assertThat(capture.of(plan).entryDeviationR()).isNull();
    }

    /**
     * D-112 and Q-77: the score at the fill uses the preset and version stored at activation, on that preset's own
     * timeframe, for the latest candle closed at the fill time; the indicators use the plan's analysis timeframe.
     */
    @Test
    void BR70_theScoreAndIndicators_areReadForTheLatestClosedCandleAtTheFill() {
        TradingPlan plan = plan(Direction.LONG, "100", "100", "95");
        scored(plan, "70.00", 60);
        ClosedCandleIndicators read = indicators();
        when(market.closedCandleIndicators(PAIR, MarketType.FUTURES, "1h", FILLED_AT))
                .thenReturn(Optional.of(read));

        SimulatedFillLearning learning = capture.of(plan);

        verify(market).closedCandleSetupScore(PAIR, MarketType.FUTURES, "4h", SetupStyle.SWING, "v1", FILLED_AT);
        assertThat(learning.setupScorePreset()).isEqualTo("SWING v1");
        assertThat(learning.setupScoreAtPlan()).isEqualByComparingTo("70");
        assertThat(learning.setupScoreAtFill()).isEqualTo(new BigDecimal("60.00"));
        assertThat(learning.indicators()).isEqualTo(read);
    }

    /** MSG55: a drop of exactly the threshold, 70 → 55, sets the warning. */
    @Test
    void MSG55_aDropOfExactly15_warns() {
        TradingPlan plan = plan(Direction.LONG, "100", "100", "95");
        scored(plan, "70.00", 55);

        assertThat(capture.of(plan).setupScoreDropWarned()).isTrue();
    }

    /** MSG55: a drop of 14, 69 → 55, does not. */
    @Test
    void MSG55_aDropOf14_doesNotWarn() {
        TradingPlan plan = plan(Direction.LONG, "100", "100", "95");
        scored(plan, "69.00", 55);

        assertThat(capture.of(plan).setupScoreDropWarned()).isFalse();
    }

    /** D-112: an unknown score at the fill compares nothing: no flag at all, not false. */
    @Test
    void BR70_anUnknownScoreAtTheFill_comparesNothing() {
        TradingPlan plan = plan(Direction.LONG, "100", "100", "95");
        scored(plan, "70.00", null);

        SimulatedFillLearning learning = capture.of(plan);

        assertThat(learning.setupScoreAtFill()).isNull();
        assertThat(learning.setupScoreDropWarned()).isNull();
    }

    /** D-112: without a score at activation (no preset stored) nothing is read or compared. */
    @Test
    void BR70_anUnknownScoreAtThePlan_readsAndComparesNothing() {
        SimulatedFillLearning learning = capture.of(plan(Direction.LONG, "100", "100", "95"));

        assertThat(learning.setupScoreAtFill()).isNull();
        assertThat(learning.setupScoreDropWarned()).isNull();
        verify(market, never()).closedCandleSetupScore(any(), any(), anyString(), any(), any(), any());
    }

    /** BR-70: a read that fails leaves its value null and never stops the fill. */
    @Test
    void BR70_failedReads_leaveNulls_andNeverThrow() {
        TradingPlan plan = plan(Direction.LONG, "100", "100", "95");
        when(plan.getSetupScoreAtPlan()).thenReturn(new BigDecimal("70.00"));
        when(plan.getSetupScorePreset()).thenReturn("SWING v1");
        when(market.closedCandleSetupScore(any(), any(), anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("database down"));
        when(market.closedCandleIndicators(any(), any(), anyString(), any()))
                .thenThrow(new IllegalStateException("database down"));

        SimulatedFillLearning learning = capture.of(plan);

        assertThat(learning.setupScoreAtFill()).isNull();
        assertThat(learning.setupScoreDropWarned()).isNull();
        assertThat(learning.indicators()).isNull();
        assertThat(learning.plannedEntryPrice()).isEqualByComparingTo("100");
    }

    private void scored(TradingPlan plan, String atPlan, Integer atFill) {
        when(plan.getSetupScoreAtPlan()).thenReturn(new BigDecimal(atPlan));
        when(plan.getSetupScorePreset()).thenReturn("SWING v1");
        when(market.closedCandleSetupScore(PAIR, MarketType.FUTURES, "4h", SetupStyle.SWING, "v1", FILLED_AT))
                .thenReturn(new SetupScoreReading(atFill, SetupStyle.SWING, "v1", "4h", CANDLE));
    }

    private static ClosedCandleIndicators indicators() {
        return new ClosedCandleIndicators(
                "1h",
                CANDLE,
                new BigDecimal("48.2"),
                new BigDecimal("99.5"),
                new BigDecimal("98.1"),
                new BigDecimal("90.4"),
                new BigDecimal("0.31"),
                new BigDecimal("0.27"),
                new BigDecimal("0.04"));
    }

    /** A Futures plan of quantity 2 at risk 10, analysed on 1h, filled at {@code fill}. */
    private static TradingPlan plan(Direction direction, String planned, String fill, String stop) {
        TradingPlan plan = mock(TradingPlan.class);
        when(plan.getId()).thenReturn(PLAN);
        when(plan.getPairId()).thenReturn(PAIR);
        when(plan.getStatus()).thenReturn(PlanStatus.EXECUTED);
        when(plan.getMarket()).thenReturn(MarketType.FUTURES);
        when(plan.getDirection()).thenReturn(direction);
        when(plan.getPlannedEntryPrice()).thenReturn(planned == null ? null : new BigDecimal(planned));
        when(plan.getFillPrice()).thenReturn(new BigDecimal(fill));
        when(plan.getStopLoss()).thenReturn(new BigDecimal(stop));
        when(plan.getExecutedAt()).thenReturn(FILLED_AT);
        when(plan.getAnalysisTimeframe()).thenReturn(AnalysisTimeframe.ONE_HOUR);
        when(plan.getSnapshot())
                .thenReturn(new PlanSnapshot(
                        new BigDecimal("2"),
                        new BigDecimal("200"),
                        new BigDecimal("20"),
                        null,
                        BigDecimal.TEN,
                        BigDecimal.TEN,
                        BigDecimal.ONE,
                        null,
                        null));
        return plan;
    }
}
