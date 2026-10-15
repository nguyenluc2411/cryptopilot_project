package com.cryptopilot.watchlist.calculator;

import com.cryptopilot.market.event.IndicatorLevels;
import com.cryptopilot.market.event.IndicatorsComputed;
import com.cryptopilot.watchlist.model.IndicatorReading;
import com.cryptopilot.watchlist.model.enums.AlertIndicator;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * EMA_CROSS: EMA20 crossing EMA50, read as EMA20 − EMA50 crossing zero.
 *
 * <p>Rule: NSF-06, BR-18, BR-20; SRS 3.4.2; TECHNICAL_DESIGN 7.9.
 *
 * <p>Reference: Murphy, J. J. (1999). <i>Technical Analysis of the Financial Markets</i>. New York Institute of
 * Finance, ch. 9 (moving average crossovers).
 */
public final class EmaCrossEvaluator implements IndicatorEvaluator {

    @Override
    public AlertIndicator indicator() {
        return AlertIndicator.EMA_CROSS;
    }

    @Override
    public Optional<IndicatorReading> read(IndicatorsComputed candle) {
        BigDecimal current = spread(candle.current());
        return current == null
                ? Optional.empty()
                : Optional.of(new IndicatorReading(spread(candle.previous()), current));
    }

    private static BigDecimal spread(IndicatorLevels levels) {
        return levels.ema20() == null || levels.ema50() == null
                ? null
                : levels.ema20().subtract(levels.ema50());
    }
}
