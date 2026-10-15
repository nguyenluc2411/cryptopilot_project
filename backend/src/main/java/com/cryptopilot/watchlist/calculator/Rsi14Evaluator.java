package com.cryptopilot.watchlist.calculator;

import com.cryptopilot.market.event.IndicatorsComputed;
import com.cryptopilot.watchlist.model.IndicatorReading;
import com.cryptopilot.watchlist.model.enums.AlertIndicator;
import java.util.Optional;

/**
 * RSI_14: the 14-period RSI of the closed candle, compared with a level from 0 to 100.
 *
 * <p>Rule: NSF-06, BR-18, BR-20; SRS 3.4.2, 3.4.4.
 *
 * <p>Reference: Wilder, J. W. (1978). <i>New Concepts in Technical Trading Systems</i>. Trend Research (RSI).
 */
public final class Rsi14Evaluator implements IndicatorEvaluator {

    @Override
    public AlertIndicator indicator() {
        return AlertIndicator.RSI_14;
    }

    @Override
    public Optional<IndicatorReading> read(IndicatorsComputed candle) {
        return candle.current().rsi14() == null
                ? Optional.empty()
                : Optional.of(new IndicatorReading(
                        candle.previous().rsi14(), candle.current().rsi14()));
    }
}
