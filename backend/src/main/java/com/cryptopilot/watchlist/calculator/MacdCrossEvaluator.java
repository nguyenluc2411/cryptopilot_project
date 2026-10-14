package com.cryptopilot.watchlist.calculator;

import com.cryptopilot.market.event.IndicatorsComputed;
import com.cryptopilot.watchlist.model.IndicatorReading;
import com.cryptopilot.watchlist.model.enums.AlertIndicator;
import java.util.Optional;

/**
 * MACD_CROSS: the MACD line crossing its signal line, read as the MACD histogram (line minus signal) crossing zero.
 *
 * <p>Rule: NSF-06, BR-18, BR-20; SRS 3.4.2; TECHNICAL_DESIGN 7.9.
 *
 * <p>Reference: Appel, G. (2005). <i>Technical Analysis: Power Tools for Active Investors</i>. FT Prentice Hall
 * (MACD and its signal line).
 */
public final class MacdCrossEvaluator implements IndicatorEvaluator {

    @Override
    public AlertIndicator indicator() {
        return AlertIndicator.MACD_CROSS;
    }

    @Override
    public Optional<IndicatorReading> read(IndicatorsComputed candle) {
        return candle.current().macdHistogram() == null
                ? Optional.empty()
                : Optional.of(new IndicatorReading(
                        candle.previous().macdHistogram(), candle.current().macdHistogram()));
    }
}
