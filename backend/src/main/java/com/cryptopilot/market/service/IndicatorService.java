package com.cryptopilot.market.service;

import com.cryptopilot.market.event.CandleClosed;
import com.cryptopilot.market.model.IndicatorSnapshot;
import com.cryptopilot.market.model.IndicatorUpdate;
import com.cryptopilot.market.model.SeriesKey;
import java.util.Optional;

/**
 * The incremental indicators of T-025, one state per series; implemented by
 * {@link com.cryptopilot.market.service.impl.IndicatorServiceImpl}.
 *
 * <p>Rule: BR-12; NSF-05; TECHNICAL_DESIGN 7.2; D-48.
 */
public interface IndicatorService {

    /**
     * Takes a closed candle of a series. The next candle updates every indicator once; the last one again changes
     * nothing; a candle out of order or after a gap is refused, logged, and the series rebuilt from its stored candles;
     * a series with no state yet is built from them. Candles of one series must be offered one at a time, as the
     * ordered consumer of NSF-03 publishes them; different series may be offered concurrently.
     */
    IndicatorUpdate onCandleClosed(CandleClosed candle);

    /** The indicators of a series after its latest candle taken, or empty when it has no state in memory. */
    Optional<IndicatorSnapshot> current(SeriesKey series);
}
