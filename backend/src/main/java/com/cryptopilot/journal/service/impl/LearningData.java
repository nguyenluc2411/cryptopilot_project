package com.cryptopilot.journal.service.impl;

import com.cryptopilot.journal.FillLearning;
import com.cryptopilot.journal.entity.TradingJournal;
import com.cryptopilot.journal.model.FillIndicators;
import com.cryptopilot.market.ClosedCandleIndicators;
import com.cryptopilot.trading.TradeNetPnl;
import com.cryptopilot.trading.model.enums.PositionStatus;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * The learning data of a simulated record with its derived figures, for the detail of SCR-21 and for
 * {@link com.cryptopilot.journal.FillLearningReader}, so both read the same values. The net P/L is the one of the list
 * (D-139, D-140): a missing fee counts as 0.
 *
 * <p>Rule: BR-38, BR-42, BR-70; D-97, D-111 (M4), D-139.
 */
final class LearningData {

    private LearningData() {}

    static FillLearning of(TradingJournal record) {
        boolean closed = record.getStatus() == PositionStatus.CLOSED;
        BigDecimal net = closed && record.getRealizedPnl() != null
                ? TradeNetPnl.of(
                        record.getRealizedPnl(),
                        Objects.requireNonNullElse(record.getFeeAmount(), BigDecimal.ZERO),
                        record.getFundingFeeAmount())
                : null;
        BigDecimal risk = record.getRiskAmount();
        return new FillLearning(
                record.getId(),
                record.getPlanId(),
                record.getPairId(),
                record.getMarket(),
                record.getDirection(),
                record.getStatus(),
                record.getEntryTime(),
                record.getExitTime(),
                record.getEntryPrice(),
                record.getPlannedEntryPrice(),
                record.getEntryDeviationR(),
                record.getEntryLiquidity(),
                risk,
                record.getAnalysisTimeframe(),
                record.getSetupScorePreset(),
                record.getSetupScoreAtPlan(),
                record.getSetupScoreAtFill(),
                record.getSetupScoreDropWarned(),
                indicators(record.indicatorsAtFill()),
                record.getMaxAdversePrice(),
                record.getMaxFavorablePrice(),
                net,
                LearningFigures.rMultiple(net, risk),
                LearningFigures.maeR(
                        record.getDirection(),
                        record.getEntryPrice(),
                        record.getMaxAdversePrice(),
                        record.getQuantity(),
                        risk),
                LearningFigures.mfeR(
                        record.getDirection(),
                        record.getEntryPrice(),
                        record.getMaxFavorablePrice(),
                        record.getQuantity(),
                        risk));
    }

    private static ClosedCandleIndicators indicators(FillIndicators stored) {
        return stored == null
                ? null
                : new ClosedCandleIndicators(
                        stored.timeframe(),
                        stored.openTime(),
                        stored.rsi14(),
                        stored.ema20(),
                        stored.ema50(),
                        stored.ema200(),
                        stored.macdLine(),
                        stored.macdSignal(),
                        stored.macdHistogram());
    }
}
