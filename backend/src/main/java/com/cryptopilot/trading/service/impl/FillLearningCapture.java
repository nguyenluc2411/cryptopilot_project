package com.cryptopilot.trading.service.impl;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.market.ClosedCandleIndicators;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.SetupScorePreset;
import com.cryptopilot.market.SetupScoreReading;
import com.cryptopilot.trading.SimulatedFillLearning;
import com.cryptopilot.trading.calculator.EntryDeviationCalculator;
import com.cryptopilot.trading.entity.TradingPlan;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Takes the learning data of a fill (BR-70) from the plan that has just filled and the market at the fill time. A read
 * that fails or finds nothing leaves its value {@code null}, logged with the plan, and never refuses or delays the
 * fill.
 *
 * <ul>
 *   <li>Deviation in R by BR-69 against the planned entry and the plan's stop loss.
 *   <li>The setup score at the fill with the preset and version stored at activation, on that preset's own timeframe,
 *       from the latest candle closed at the fill time (D-112 and its note of 2026-10-23).
 *   <li>The indicators from the latest candle of the plan's analysis timeframe closed at the fill time.
 *   <li>The drop is compared only when both scores are known; a drop of at least {@code SETUP_SCORE_DROP_WARN} sets
 *       the warning.
 * </ul>
 *
 * <p>Rule: BR-36, BR-69, BR-70; MSG55; D-111 (M2, M3, M4), D-112; Q-77.
 *
 * <p>Reference: Tharp, V. K. (2006). <i>Trade Your Way to Financial Freedom</i> (2nd ed.). McGraw-Hill.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class FillLearningCapture {

    static final String SETUP_SCORE_DROP_WARN = "SETUP_SCORE_DROP_WARN";

    private static final Logger log = LoggerFactory.getLogger(FillLearningCapture.class);

    private final MarketApi market;
    private final SystemSettingApi settings;

    /** The learning data of an EXECUTED plan's fill. */
    public SimulatedFillLearning of(TradingPlan plan) {
        BigDecimal planned = plan.getPlannedEntryPrice();
        BigDecimal scoreAtPlan = plan.getSetupScoreAtPlan();
        BigDecimal scoreAtFill = scoreAtFill(plan);
        String timeframe = plan.getAnalysisTimeframe() == null
                ? null
                : plan.getAnalysisTimeframe().code();
        return new SimulatedFillLearning(
                planned,
                deviation(plan, planned),
                plan.getSnapshot() == null ? null : plan.getSnapshot().riskAmount(),
                timeframe,
                plan.getSetupScorePreset(),
                scoreAtPlan,
                scoreAtFill,
                dropWarned(scoreAtPlan, scoreAtFill),
                indicators(plan, timeframe));
    }

    /**
     * Whether the score fell by at least the warning threshold; {@code null} when either score is unknown.
     *
     * <p>Rule: BR-70; D-112.
     */
    Boolean dropWarned(BigDecimal scoreAtPlan, BigDecimal scoreAtFill) {
        if (scoreAtPlan == null || scoreAtFill == null) {
            return null;
        }
        BigDecimal threshold = BigDecimal.valueOf(settings.integer(SETUP_SCORE_DROP_WARN));
        return scoreAtPlan.subtract(scoreAtFill).compareTo(threshold) >= 0;
    }

    private static BigDecimal deviation(TradingPlan plan, BigDecimal planned) {
        if (planned == null || planned.compareTo(plan.getStopLoss()) == 0) {
            return null;
        }
        return EntryDeviationCalculator.deviationR(
                plan.getDirection(), planned, plan.getFillPrice(), plan.getStopLoss());
    }

    private BigDecimal scoreAtFill(TradingPlan plan) {
        Optional<SetupScorePreset> preset = SetupScorePreset.parse(plan.getSetupScorePreset());
        if (preset.isEmpty()) {
            return null;
        }
        Instant at = plan.getExecutedAt();
        try {
            SetupScoreReading reading = market.closedCandleSetupScore(
                    plan.getPairId(),
                    plan.getMarket(),
                    market.presetTimeframe(preset.get().style()),
                    preset.get().style(),
                    preset.get().version(),
                    at);
            // At the column's scale, as the score at activation is stored.
            return reading.score() == null
                    ? null
                    : BigDecimal.valueOf(reading.score()).setScale(2);
        } catch (RuntimeException e) {
            log.warn("BR-70 plan {} filled without a setup score at the fill: the read failed", plan.getId(), e);
            return null;
        }
    }

    private ClosedCandleIndicators indicators(TradingPlan plan, String timeframe) {
        if (timeframe == null) {
            return null;
        }
        try {
            return market.closedCandleIndicators(plan.getPairId(), plan.getMarket(), timeframe, plan.getExecutedAt())
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("BR-70 plan {} filled without indicators: the read failed", plan.getId(), e);
            return null;
        }
    }
}
