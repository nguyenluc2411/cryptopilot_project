package com.cryptopilot.market.service.impl;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.SetupStyle;
import com.cryptopilot.market.SetupStyleSource;
import com.cryptopilot.market.calculator.SetupComponents;
import com.cryptopilot.market.calculator.SetupScoreCalculator;
import com.cryptopilot.market.config.SetupScoreProperties;
import com.cryptopilot.market.dto.response.AnalysisResponse;
import com.cryptopilot.market.dto.response.ComponentScoresResponse;
import com.cryptopilot.market.dto.response.FuturesComponentsResponse;
import com.cryptopilot.market.dto.response.FuturesSetupScoreResponse;
import com.cryptopilot.market.dto.response.IndicatorsResponse;
import com.cryptopilot.market.dto.response.SetupScoreResponse;
import com.cryptopilot.market.dto.response.SpotComponentsResponse;
import com.cryptopilot.market.dto.response.SpotSetupScoreResponse;
import com.cryptopilot.market.entity.CryptoPair;
import com.cryptopilot.market.model.ComponentScores;
import com.cryptopilot.market.model.DominantSide;
import com.cryptopilot.market.model.IndicatorSnapshot;
import com.cryptopilot.market.model.SetupScore;
import com.cryptopilot.market.model.StoredCandle;
import com.cryptopilot.market.model.StoredIndicators;
import com.cryptopilot.market.model.StylePreset;
import com.cryptopilot.market.repository.OhlcvRepository;
import com.cryptopilot.market.repository.TechnicalIndicatorRepository;
import com.cryptopilot.market.service.AnalysisService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The analysis of UC-10 and UC-11, read from what NSF-05 stored and nothing else: the row of the latest closed candle
 * of the requested timeframe, and the setup score weighted now from the components of the preset's own timeframe
 * (D-53 rule 3: no score is stored per user or per preset). A value NSF-05 could not compute is answered as
 * {@code null}; nothing is recomputed or filled in here.
 *
 * <p>The preset is the caller's, as their profile selects it through {@link SetupStyleSource}; without a caller, or
 * while no module provides that source, it is {@link #DEFAULT_STYLE}. On Futures the dominant side of the trend is
 * read beside the score from the stored EMAs and the close of the same candle in {@code ohlcv}, and never stored.
 *
 * <p>Rule: UC-10, UC-11, BR-07, BR-08, BR-12, BR-13, BR-14; SRS 3.3.2; TECHNICAL_DESIGN 7.4; D-53.
 */
@Service
public class AnalysisServiceImpl implements AnalysisService {

    /** The preset of a caller whose profile selects none, and of every caller until a style source exists (D-53). */
    static final SetupStyle DEFAULT_STYLE = SetupStyle.DAY_TRADING;

    /** The chart's timeframe when the request names none (SRS 3.3.2). */
    static final String DEFAULT_TIMEFRAME = "1h";

    private final MarketRequests requests;
    private final TechnicalIndicatorRepository indicators;
    private final OhlcvRepository candles;
    private final SetupScoreProperties presets;
    private final ObjectProvider<SetupStyleSource> styles;

    public AnalysisServiceImpl(
            MarketRequests requests,
            TechnicalIndicatorRepository indicators,
            OhlcvRepository candles,
            SetupScoreProperties presets,
            ObjectProvider<SetupStyleSource> styles) {
        this.requests = requests;
        this.indicators = indicators;
        this.candles = candles;
        this.presets = presets;
        this.styles = styles;
    }

    @Override
    @Transactional(readOnly = true)
    public AnalysisResponse analysis(String market, String symbol, String timeframe, UUID userId) {
        MarketType type = MarketRequests.market(market);
        String tf = MarketRequests.timeframe(timeframe == null ? DEFAULT_TIMEFRAME : timeframe)
                .code();
        CryptoPair pair = requests.enabledPair(type, symbol);
        Optional<StoredIndicators> row = indicators.latest(pair.getId(), type, tf);
        StylePreset preset = presets.preset(styleOf(userId));
        Optional<StoredIndicators> scored =
                preset.timeframe().equals(tf) ? row : indicators.latest(pair.getId(), type, preset.timeframe());
        return new AnalysisResponse(
                pair.getSymbol(),
                type.name(),
                tf,
                row.map(StoredIndicators::openTime).orElse(null),
                row.map(StoredIndicators::calculatedAt).orElse(null),
                row.map(AnalysisServiceImpl::indicators).orElse(IndicatorsResponse.NONE),
                row.map(StoredIndicators::nearestSupport).orElse(null),
                row.map(StoredIndicators::nearestResistance).orElse(null),
                components(type, row.map(StoredIndicators::components).orElse(null)),
                setupScore(type, preset, pair.getId(), scored.orElse(null)));
    }

    private SetupStyle styleOf(UUID userId) {
        SetupStyleSource source = styles.getIfAvailable();
        if (userId == null || source == null) {
            return DEFAULT_STYLE;
        }
        return source.styleOf(userId).orElse(DEFAULT_STYLE);
    }

    private SetupScoreResponse setupScore(MarketType type, StylePreset preset, UUID pairId, StoredIndicators row) {
        if (row == null) {
            return type == MarketType.SPOT
                    ? new SpotSetupScoreResponse(
                            null, preset.style(), preset.version(), preset.timeframe(), null, spot(null))
                    : new FuturesSetupScoreResponse(
                            null, preset.style(), preset.version(), preset.timeframe(), null, futures(null), null);
        }
        if (type == MarketType.SPOT) {
            SetupScore s = SetupScoreCalculator.score(preset, row.components(), null);
            return new SpotSetupScoreResponse(
                    s.score(), s.style(), s.presetVersion(), s.timeframe(), row.openTime(), spot(row.components()));
        }
        SetupScore s = SetupScoreCalculator.score(preset, row.components(), dominantSide(pairId, row));
        return new FuturesSetupScoreResponse(
                s.score(),
                s.style(),
                s.presetVersion(),
                s.timeframe(),
                row.openTime(),
                futures(row.components()),
                s.dominantSide());
    }

    /** From the stored EMAs and the close of the same candle; {@code null} when either is missing. */
    private DominantSide dominantSide(UUID pairId, StoredIndicators row) {
        String tf = row.components().timeframe();
        List<StoredCandle> candle = candles.closedCandles(
                pairId, MarketType.FUTURES, tf, row.openTime(), row.openTime().plusSeconds(1), 1);
        if (candle.isEmpty()) {
            return null;
        }
        return SetupComponents.dominantSide(
                MarketType.FUTURES, candle.getFirst().close(), emas(row));
    }

    /** The stored EMAs in the shape the trend reading takes; the other indicators are not read by it. */
    private static IndicatorSnapshot emas(StoredIndicators row) {
        return new IndicatorSnapshot(
                row.openTime(),
                0,
                null,
                toDouble(row.ema20()),
                toDouble(row.ema50()),
                toDouble(row.ema200()),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private static ComponentScoresResponse components(MarketType type, ComponentScores c) {
        return type == MarketType.SPOT ? spot(c) : futures(c);
    }

    private static SpotComponentsResponse spot(ComponentScores c) {
        return c == null
                ? new SpotComponentsResponse(null, null, null, null, null)
                : new SpotComponentsResponse(c.formulaVersion(), c.trend(), c.momentum(), c.volume(), c.level());
    }

    private static FuturesComponentsResponse futures(ComponentScores c) {
        return c == null
                ? new FuturesComponentsResponse(null, null, null, null, null, null)
                : new FuturesComponentsResponse(
                        c.formulaVersion(), c.trend(), c.momentum(), c.volume(), c.level(), c.derivatives());
    }

    private static IndicatorsResponse indicators(StoredIndicators r) {
        return new IndicatorsResponse(
                r.sma20(),
                r.ema20(),
                r.ema50(),
                r.ema200(),
                r.rsi14(),
                r.macdLine(),
                r.macdSignal(),
                r.macdHistogram(),
                r.bbUpper(),
                r.bbMiddle(),
                r.bbLower(),
                r.volumeSma20());
    }
}
