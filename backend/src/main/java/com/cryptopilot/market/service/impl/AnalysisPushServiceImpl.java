package com.cryptopilot.market.service.impl;

import com.cryptopilot.market.dto.response.AnalysisComponentsUpdateResponse;
import com.cryptopilot.market.dto.response.AnalysisUpdateResponse;
import com.cryptopilot.market.event.CandleClosed;
import com.cryptopilot.market.model.SeriesKey;
import com.cryptopilot.market.model.StoredIndicators;
import com.cryptopilot.market.service.AnalysisPushService;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Service;

/**
 * NSF-05 "pushes the result to clients viewing the pair": after the row of a closed candle is stored, two messages for
 * its series. {@code /topic/analysis.{market}.{symbol}.{tf}} carries what is the same for every Trader — indicators,
 * support and resistance, the candle's open time — and never the setup score, which depends on the reader's preset and
 * is re-read through the REST analysis; {@code .components} carries the component scores. Who may subscribe is
 * {@link AnalysisTopicGuard}'s rule.
 *
 * <p>A close can be announced again for a candle already processed ({@link CandleClosed}); the latest open time pushed
 * per series is kept, so each candle is pushed once by this instance and an older one never after a newer one. A
 * restart forgets it, so the latest candle may be pushed once more after one.
 *
 * <p>Rule: NSF-05; UC-10, UC-11; SRS 3.3.5; TECHNICAL_DESIGN 9; D-51, D-126.
 */
@Service
public class AnalysisPushServiceImpl implements AnalysisPushService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisPushServiceImpl.class);

    private final Map<SeriesKey, Instant> pushed = new ConcurrentHashMap<>();
    private final SimpMessageSendingOperations broker;

    public AnalysisPushServiceImpl(SimpMessageSendingOperations broker) {
        this.broker = broker;
    }

    @Override
    public boolean publish(CandleClosed candle, StoredIndicators row) {
        AtomicBoolean fresh = new AtomicBoolean();
        pushed.compute(new SeriesKey(candle.pairId(), candle.market(), candle.timeframe()), (series, last) -> {
            if (last != null && !row.openTime().isAfter(last)) {
                return last;
            }
            fresh.set(true);
            return row.openTime();
        });
        if (!fresh.get()) {
            return false;
        }
        String market = candle.market().name();
        String destination = destination(market, candle.symbol(), candle.timeframe());
        send(
                destination,
                new AnalysisUpdateResponse(
                        market,
                        candle.symbol(),
                        candle.timeframe(),
                        row.openTime(),
                        row.calculatedAt(),
                        AnalysisServiceImpl.indicators(row),
                        row.nearestSupport(),
                        row.nearestResistance()));
        send(
                destination + ".components",
                new AnalysisComponentsUpdateResponse(
                        market,
                        candle.symbol(),
                        candle.timeframe(),
                        row.openTime(),
                        AnalysisServiceImpl.components(candle.market(), row.components())));
        return true;
    }

    /** {@code /topic/analysis.{market}.{symbol}.{tf}}. */
    static String destination(String market, String symbol, String timeframe) {
        return AnalysisTopicGuard.PREFIX + market + "." + symbol + "." + timeframe;
    }

    private void send(String destination, Object payload) {
        try {
            broker.convertAndSend(destination, payload);
        } catch (RuntimeException failure) {
            log.warn("Analysis topic {} could not be pushed: {}", destination, failure.toString());
        }
    }
}
