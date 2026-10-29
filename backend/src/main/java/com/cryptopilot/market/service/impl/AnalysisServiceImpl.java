package com.cryptopilot.market.service.impl;

import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.model.enums.Feature;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.market.SetupScoreReading;
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
import com.cryptopilot.market.model.IndicatorSnapshot;
import com.cryptopilot.market.model.SetupScore;
import com.cryptopilot.market.model.StoredCandle;
import com.cryptopilot.market.model.StoredIndicators;
import com.cryptopilot.market.model.StylePreset;
import com.cryptopilot.market.model.enums.DominantSide;
import com.cryptopilot.market.model.enums.MarketInterval;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.model.enums.SetupStyle;
import com.cryptopilot.market.repository.OhlcvRepository;
import com.cryptopilot.market.repository.TechnicalIndicatorRepository;
import com.cryptopilot.market.service.AnalysisService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
 * <p>The caller's plan decides two things (D-60): the Futures analysis as a whole needs {@code FUTURES_ANALYSIS} and is
 * refused with MSG29 without it, before anything is read; the component scores need {@code SCORE_COMPONENTS}, and
 * without it both component blocks are withheld and marked locked while the total score is still answered (CR-08). A
 * call without a caller has no plan.
 *
 * <p>The same weighting serves other modules through {@link #closedCandleSetupScore}: the score of the latest closed
 * candle of one timeframe under a preset they name, which a plan stores at activation (D-112).
 *
 * <p>Rule: UC-10, UC-11, BR-07, BR-08, BR-12, BR-13, BR-14, BR-62, BR-70; SRS 3.3.2, CR-08; TECHNICAL_DESIGN 7.4,
 * 7.12; D-53, D-60, D-112.
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
    private final Clock clock;
    private final EntitlementApi entitlements;

    public AnalysisServiceImpl(
            MarketRequests requests,
            TechnicalIndicatorRepository indicators,
            OhlcvRepository candles,
            SetupScoreProperties presets,
            ObjectProvider<SetupStyleSource> styles,
            Clock clock,
            EntitlementApi entitlements) {
        this.requests = requests;
        this.indicators = indicators;
        this.candles = candles;
        this.presets = presets;
        this.styles = styles;
        this.clock = clock;
        this.entitlements = entitlements;
    }

    @Override
    @Transactional(readOnly = true)
    public AnalysisResponse analysis(String market, String symbol, String timeframe, UUID userId) {
        MarketType type = MarketRequests.market(market);
        String tf = MarketRequests.timeframe(timeframe == null ? DEFAULT_TIMEFRAME : timeframe)
                .code();
        if (type == MarketType.FUTURES) {
            requireFuturesAnalysis(userId);
        }
        boolean locked = userId == null || !entitlements.hasFeature(userId, Feature.SCORE_COMPONENTS);
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
                locked,
                locked
                        ? null
                        : components(type, row.map(StoredIndicators::components).orElse(null)),
                setupScore(type, preset, pair.getId(), scored.orElse(null), locked));
    }

    /** MSG29 for a plan without the Futures analysis; MSG44 for a call without a caller, who has no plan at all. */
    private void requireFuturesAnalysis(UUID userId) {
        if (userId == null) {
            throw new BusinessException(
                    ErrorCode.AUTHENTICATION_REQUIRED, "the Futures analysis needs a Trader's plan");
        }
        entitlements.requireFeature(userId, Feature.FUTURES_ANALYSIS);
    }

    /**
     * Weights the components of the latest candle closed at {@code at} with {@link SetupScoreCalculator}, as
     * {@link #analysis} does. That candle opened one interval before the last interval boundary at or before
     * {@code at} (intervals are aligned to the epoch in UTC, BR-08), and only its own row counts: when the newest
     * stored row is older, e.g. after an outage of the indicator job, the score is {@code null}, never a stale one. The
     * score is also {@code null} when a component is missing, the version asked for is not the configured one (its
     * weights are not held), or {@code timeframe} is not the preset's own: the weights of a preset are defined for its
     * timeframe only (TECHNICAL_DESIGN 7.4), which {@link #presetTimeframe} names. Futures needs no dominant side here;
     * it is never part of the score.
     *
     * <p>Rule: BR-08, BR-13, BR-70; TECHNICAL_DESIGN 7.4; D-53, D-112.
     *
     * <p>The read runs outside a caller's transaction and sees committed rows only: one query needs none, and a failed
     * read can then neither mark the caller's transaction for rollback nor abort it in the database, so the caller may
     * go on without the score (a plan's activation does).
     *
     * @throws IllegalArgumentException for a timeframe outside BR-08, or an instant after now, at which a candle still
     *     forming would be read
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SetupScoreReading closedCandleSetupScore(
            UUID pairId, MarketType market, String timeframe, SetupStyle style, String presetVersion, Instant at) {
        if (at.isAfter(clock.instant())) {
            throw new IllegalArgumentException("a setup score is read at or before now, not at " + at);
        }
        MarketInterval interval = MarketInterval.fromCode(timeframe)
                .filter(CandleBackfillServiceImpl.TIMEFRAMES::contains)
                .orElseThrow(() -> new IllegalArgumentException(timeframe + " is not a BR-08 timeframe"));
        StylePreset preset = presets.preset(style == null ? DEFAULT_STYLE : style);
        String version = presetVersion == null ? preset.version() : presetVersion;
        Instant latestClosedOpen = latestClosedOpen(interval, at);
        Optional<StoredIndicators> row = indicators.at(pairId, market, timeframe, latestClosedOpen);
        boolean weighable = version.equals(preset.version()) && timeframe.equals(preset.timeframe());
        Integer score = row.filter(r -> weighable)
                .map(r ->
                        SetupScoreCalculator.score(preset, r.components(), null).score())
                .orElse(null);
        return new SetupScoreReading(score, preset.style(), version, timeframe, latestClosedOpen);
    }

    @Override
    public String presetTimeframe(SetupStyle style) {
        return presets.preset(style == null ? DEFAULT_STYLE : style).timeframe();
    }

    /** The open time of the latest candle of {@code interval} whose close is at or before {@code at}. */
    static Instant latestClosedOpen(MarketInterval interval, Instant at) {
        long seconds = interval.duration().toSeconds();
        return Instant.ofEpochSecond(Math.floorDiv(at.getEpochSecond(), seconds) * seconds - seconds);
    }

    private SetupStyle styleOf(UUID userId) {
        SetupStyleSource source = styles.getIfAvailable();
        if (userId == null || source == null) {
            return DEFAULT_STYLE;
        }
        return source.styleOf(userId).orElse(DEFAULT_STYLE);
    }

    private SetupScoreResponse setupScore(
            MarketType type, StylePreset preset, UUID pairId, StoredIndicators row, boolean locked) {
        if (row == null) {
            return type == MarketType.SPOT
                    ? new SpotSetupScoreResponse(
                            null,
                            preset.style(),
                            preset.version(),
                            preset.timeframe(),
                            null,
                            locked ? null : spot(null))
                    : new FuturesSetupScoreResponse(
                            null,
                            preset.style(),
                            preset.version(),
                            preset.timeframe(),
                            null,
                            locked ? null : futures(null),
                            null);
        }
        if (type == MarketType.SPOT) {
            SetupScore s = SetupScoreCalculator.score(preset, row.components(), null);
            return new SpotSetupScoreResponse(
                    s.score(),
                    s.style(),
                    s.presetVersion(),
                    s.timeframe(),
                    row.openTime(),
                    locked ? null : spot(row.components()));
        }
        SetupScore s = SetupScoreCalculator.score(preset, row.components(), dominantSide(pairId, row));
        return new FuturesSetupScoreResponse(
                s.score(),
                s.style(),
                s.presetVersion(),
                s.timeframe(),
                row.openTime(),
                locked ? null : futures(row.components()),
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
