package com.cryptopilot.market.job;

import com.cryptopilot.market.event.CandleClosed;
import com.cryptopilot.market.service.AnalysisPushService;
import com.cryptopilot.market.service.IndicatorPipelineService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * NSF-05 on each closed candle, on the thread that published it, so the candles of a series reach the indicators in
 * the order NSF-03 stored them; once the row is stored and committed, it is pushed to the analysis topics. A failure is
 * logged and does not reach the stream.
 *
 * <p>Rule: NSF-05; TECHNICAL_DESIGN 7.2, 9 and 10; D-126.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class IndicatorPipelineJob {

    private static final Logger log = LoggerFactory.getLogger(IndicatorPipelineJob.class);

    private final IndicatorPipelineService pipeline;
    private final AnalysisPushService push;

    /** A candle closed and was stored: compute and store its indicators and component scores, then push them. */
    @EventListener
    public void onCandleClosed(CandleClosed candle) {
        try {
            pipeline.onCandleClosed(candle).ifPresent(row -> push.publish(candle, row));
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
