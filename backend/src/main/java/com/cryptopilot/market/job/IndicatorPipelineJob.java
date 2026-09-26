package com.cryptopilot.market.job;

import com.cryptopilot.market.event.CandleClosed;
import com.cryptopilot.market.service.IndicatorPipelineService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * NSF-05 on each closed candle, on the thread that published it, so the candles of a series reach the indicators in
 * the order NSF-03 stored them. A failure is logged and does not reach the stream.
 *
 * <p>Rule: NSF-05; TECHNICAL_DESIGN 7.2 and 10.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class IndicatorPipelineJob {

    private static final Logger log = LoggerFactory.getLogger(IndicatorPipelineJob.class);

    private final IndicatorPipelineService pipeline;

    /** A candle closed and was stored: compute and store its indicators and component scores. */
    @EventListener
    public void onCandleClosed(CandleClosed candle) {
        try {
            pipeline.onCandleClosed(candle);
        } catch (RuntimeException failure) {
            log.error(
                    "NSF-05 {} {} {}: the candle opened at {} was not processed",
                    candle.symbol(),
                    candle.market(),
                    candle.timeframe(),
                    candle.openTime(),
                    failure);
        }
    }
}
