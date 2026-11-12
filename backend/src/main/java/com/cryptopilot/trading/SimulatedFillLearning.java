package com.cryptopilot.trading;

import com.cryptopilot.market.ClosedCandleIndicators;
import java.math.BigDecimal;

/**
 * The learning data a simulated fill records beside its position: how far the entry was from the plan, the risk the
 * plan took, and the setup at activation and at the fill. Every value is computed by the {@code trading} module; the
 * journal stores them. Any value may be {@code null} when it could not be known, and that never blocks the fill.
 *
 * <p>Rule: BR-36, BR-69, BR-70; D-111 (M2, M4), D-112.
 *
 * <p>Reference: Tharp, V. K. (2006). <i>Trade Your Way to Financial Freedom</i> (2nd ed.). McGraw-Hill (deviation and
 * risk in R).
 *
 * @param plannedEntryPrice the entered price for LIMIT and STOP, the reference price of the last draft save for MARKET
 * @param entryDeviationR the deviation of the fill from the planned entry in R; positive is a worse entry
 * @param riskAmount the loss at the stop loss the plan was sized on
 * @param analysisTimeframe the plan's chart timeframe, the one the indicators are read on
 * @param setupScorePreset the preset name and version the score at activation used, e.g. {@code DAY_TRADING v1}
 * @param setupScoreAtPlan the setup score at activation
 * @param setupScoreAtFill the setup score at the fill, with the same preset and version
 * @param setupScoreDropWarned whether the score fell by at least {@code SETUP_SCORE_DROP_WARN}; {@code null} when one
 *     of the scores is unknown, so nothing was compared
 * @param indicators the indicators of the latest closed candle of the analysis timeframe at the fill
 */
public record SimulatedFillLearning(
        BigDecimal plannedEntryPrice,
        BigDecimal entryDeviationR,
        BigDecimal riskAmount,
        String analysisTimeframe,
        String setupScorePreset,
        BigDecimal setupScoreAtPlan,
        BigDecimal setupScoreAtFill,
        Boolean setupScoreDropWarned,
        ClosedCandleIndicators indicators) {

    /** No learning data, for a fill recorded without it. */
    public static SimulatedFillLearning none() {
        return new SimulatedFillLearning(null, null, null, null, null, null, null, null, null);
    }
}
