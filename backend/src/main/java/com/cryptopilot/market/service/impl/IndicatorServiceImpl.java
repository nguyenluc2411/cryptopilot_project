package com.cryptopilot.market.service.impl;

import com.cryptopilot.market.calculator.IndicatorEngine;
import com.cryptopilot.market.client.MarketInterval;
import com.cryptopilot.market.config.IndicatorProperties;
import com.cryptopilot.market.event.CandleClosed;
import com.cryptopilot.market.model.IndicatorOutcome;
import com.cryptopilot.market.model.IndicatorSnapshot;
import com.cryptopilot.market.model.IndicatorUpdate;
import com.cryptopilot.market.model.SeriesKey;
import com.cryptopilot.market.model.StoredCandle;
import com.cryptopilot.market.repository.OhlcvRepository;
import com.cryptopilot.market.service.IndicatorService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Keeps one {@link IndicatorEngine} per series in memory and feeds it the closed candles as they come.
 *
 * <h2>Restore and rebuild</h2>
 *
 * <p>A series with no engine (the first candle after start-up), and a series offered a candle out of order or after a
 * gap, is rebuilt from its latest {@code historyCandles} stored closed candles (TECHNICAL_DESIGN 7.2: 1000), up to the
 * later of the offered candle and the last one taken. NSF-03 publishes a close after storing it, so the offered candle
 * is normally among them. Stored candles with a hole cannot give right values across it, so the engine starts over
 * after the last hole and warms up again, with {@code null} values until it has enough candles (SRS 3.3.2). The
 * backfill fills the hole (NSF-02) and a later rebuild spans it.
 *
 * <h2>Concurrency</h2>
 *
 * <p>The candles of one series arrive one at a time on the ordered consumer of their pair; the lock per series only
 * guards against a caller that does not keep to that. Series never wait for each other.
 *
 * <p>Rule: BR-12; NSF-05; SRS 3.3.2; TECHNICAL_DESIGN 7.2 and 10.
 */
@Service
public class IndicatorServiceImpl implements IndicatorService {

    private static final Logger log = LoggerFactory.getLogger(IndicatorService.class);

    private final OhlcvRepository candles;
    private final int historyCandles;
    private final Map<SeriesKey, Series> series = new ConcurrentHashMap<>();

    public IndicatorServiceImpl(OhlcvRepository candles, IndicatorProperties properties) {
        this.candles = candles;
        this.historyCandles = properties.historyCandles();
    }

    @Override
    public IndicatorUpdate onCandleClosed(CandleClosed candle) {
        SeriesKey key = new SeriesKey(candle.pairId(), candle.market(), candle.timeframe());
        Duration interval = MarketInterval.fromCode(candle.timeframe())
                .orElseThrow(() -> new IllegalArgumentException("unknown timeframe " + candle.timeframe()))
                .duration();
        Series state = series.computeIfAbsent(key, k -> new Series());
        synchronized (state) {
            if (state.engine == null) {
                state.engine = rebuild(key, interval, candle.openTime());
                return new IndicatorUpdate(IndicatorOutcome.RESTORED, state.engine.snapshot());
            }
            IndicatorOutcome outcome = state.engine.offer(
                    candle.openTime(),
                    candle.close().doubleValue(),
                    candle.baseVolume().doubleValue());
            if (outcome == IndicatorOutcome.ACCEPTED || outcome == IndicatorOutcome.DUPLICATE) {
                return new IndicatorUpdate(outcome, state.engine.snapshot());
            }
            Instant last = state.engine.lastOpenTime();
            log.warn(
                    "Indicators of {} refused the candle opened at {} ({}) after the one opened at {};"
                            + " rebuilding from the stored candles",
                    key,
                    candle.openTime(),
                    outcome,
                    last);
            Instant upTo = candle.openTime().isAfter(last) ? candle.openTime() : last;
            state.engine = rebuild(key, interval, upTo);
            return new IndicatorUpdate(outcome, state.engine.snapshot());
        }
    }

    @Override
    public Optional<IndicatorSnapshot> current(SeriesKey key) {
        Series state = series.get(key);
        if (state == null) {
            return Optional.empty();
        }
        synchronized (state) {
            return Optional.ofNullable(state.engine).map(IndicatorEngine::snapshot);
        }
    }

    /** An engine fed the latest stored candles opened up to {@code upTo}, started over after the last hole. */
    private IndicatorEngine rebuild(SeriesKey key, Duration interval, Instant upTo) {
        List<StoredCandle> history = candles.closedCandles(
                key.pairId(), key.market(), key.timeframe(), null, upTo.plus(interval), historyCandles);
        IndicatorEngine engine = new IndicatorEngine(interval);
        for (StoredCandle candle : history) {
            double close = candle.close().doubleValue();
            double volume = candle.baseVolume().doubleValue();
            if (engine.offer(candle.openTime(), close, volume) == IndicatorOutcome.GAP) {
                log.warn(
                        "Stored candles of {} miss those between {} and {}; the indicators warm up again from there",
                        key,
                        engine.lastOpenTime(),
                        candle.openTime());
                engine = new IndicatorEngine(interval);
                engine.offer(candle.openTime(), close, volume);
            }
        }
        return engine;
    }

    /** The engine of one series; {@code null} until the series is first built. Guarded by its own monitor. */
    private static final class Series {
        private IndicatorEngine engine;
    }
}
