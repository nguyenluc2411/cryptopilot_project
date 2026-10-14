package com.cryptopilot.watchlist.calculator;

import com.cryptopilot.market.event.IndicatorsComputed;
import com.cryptopilot.watchlist.model.IndicatorReading;
import com.cryptopilot.watchlist.model.enums.AlertIndicator;
import java.util.Optional;

/**
 * Reads the value one kind of indicator alert compares from a closed candle's indicators. The values are those NSF-05
 * computed and stored; nothing is computed again here.
 *
 * <p>Rule: NSF-06, BR-18; SRS 3.4.4; TECHNICAL_DESIGN 7.9.
 */
public interface IndicatorEvaluator {

    /** The indicator this evaluator reads. */
    AlertIndicator indicator();

    /** The value on the closed candle and on the one before; empty when the closed candle has none (warm-up). */
    Optional<IndicatorReading> read(IndicatorsComputed candle);
}
