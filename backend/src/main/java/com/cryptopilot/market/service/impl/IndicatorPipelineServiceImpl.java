package com.cryptopilot.market.service.impl;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.calculator.SetupComponents;
import com.cryptopilot.market.calculator.SwingSupportResistance;
import com.cryptopilot.market.client.MarketInterval;
import com.cryptopilot.market.event.CandleClosed;
import com.cryptopilot.market.model.ComponentInputs;
import com.cryptopilot.market.model.ComponentScores;
import com.cryptopilot.market.model.DerivativesInputs;
import com.cryptopilot.market.model.IndicatorSnapshot;
import com.cryptopilot.market.model.StoredIndicators;
import com.cryptopilot.market.repository.MarketDataQueryRepository;
import com.cryptopilot.market.repository.OhlcvRepository;
import com.cryptopilot.market.repository.TechnicalIndicatorRepository;
import com.cryptopilot.market.service.IndicatorPipelineService;
import com.cryptopilot.market.service.IndicatorService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the {@code technical_indicator} row of a closed candle from what T-025, T-026 and T-027 compute: the
 * indicators from {@link IndicatorService}, the nearest support and resistance over the latest 200 stored candles,
 * and the component scores. Indicators and levels are stored rounded half-up to 10 places (TECHNICAL_DESIGN 5.4).
 *
 * <p>Rule: NSF-05; BR-12, BR-13, BR-14; TECHNICAL_DESIGN 5.4, 7.2–7.4; D-53 (rule 3).
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class IndicatorPipelineServiceImpl implements IndicatorPipelineService {

    private static final Logger log = LoggerFactory.getLogger(IndicatorPipelineService.class);

    static final int SWING_CANDLES = 200;
    static final int SCALE = 10;

    private final IndicatorService indicators;
    private final OhlcvRepository candles;
    private final TechnicalIndicatorRepository store;
    private final MarketDataQueryRepository marketData;
    private final Clock clock;

    @Override
    @Transactional
    public Optional<StoredIndicators> onCandleClosed(CandleClosed candle) {
        IndicatorSnapshot snapshot = indicators.onCandleClosed(candle).snapshot();
        // An out-of-order candle leaves the series at a later candle, whose row is already stored.
        if (snapshot == null || !snapshot.openTime().equals(candle.openTime())) {
            log.debug(
                    "NSF-05 {} {} {}: no row for the candle opened at {}",
                    candle.symbol(),
                    candle.market(),
                    candle.timeframe(),
                    candle.openTime());
            return Optional.empty();
        }
        Duration interval = MarketInterval.fromCode(candle.timeframe())
                .orElseThrow(() -> new IllegalArgumentException("unknown timeframe " + candle.timeframe()))
                .duration();
        SwingSupportResistance.Levels levels = SwingSupportResistance.nearest(candles.closedCandles(
                candle.pairId(),
                candle.market(),
                candle.timeframe(),
                null,
                candle.openTime().plus(interval),
                SWING_CANDLES));
        Double previousHistogram = store.macdHistogram(
                        candle.pairId(),
                        candle.market(),
                        candle.timeframe(),
                        candle.openTime().minus(interval))
                .map(BigDecimal::doubleValue)
                .orElse(null);
        DerivativesInputs derivatives = candle.market() == MarketType.FUTURES
                ? marketData.derivativesAt(candle.pairId(), candle.closeTime())
                : null;
        ComponentScores components = SetupComponents.compute(new ComponentInputs(
                candle.market(),
                candle.timeframe(),
                candle.close(),
                candle.baseVolume(),
                snapshot,
                previousHistogram,
                levels.support(),
                levels.resistance(),
                derivatives));
        StoredIndicators row = new StoredIndicators(
                candle.pairId(),
                candle.openTime(),
                scaled(snapshot.sma20()),
                scaled(snapshot.ema20()),
                scaled(snapshot.ema50()),
                scaled(snapshot.ema200()),
                scaled(snapshot.rsi14()),
                scaled(snapshot.macdLine()),
                scaled(snapshot.macdSignal()),
                scaled(snapshot.macdHistogram()),
                scaled(snapshot.bbUpper()),
                scaled(snapshot.bbMiddle()),
                scaled(snapshot.bbLower()),
                scaled(snapshot.volumeSma20()),
                scaled(levels.support()),
                scaled(levels.resistance()),
                components,
                clock.instant());
        store.upsert(row);
        return Optional.of(row);
    }

    private static BigDecimal scaled(Double value) {
        return value == null ? null : scaled(BigDecimal.valueOf(value));
    }

    private static BigDecimal scaled(BigDecimal value) {
        return value == null ? null : value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
