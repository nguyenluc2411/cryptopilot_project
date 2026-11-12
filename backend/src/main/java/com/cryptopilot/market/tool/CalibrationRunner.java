package com.cryptopilot.market.tool;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.calculator.IndicatorEngine;
import com.cryptopilot.market.calculator.SetupComponents;
import com.cryptopilot.market.calculator.SetupScoreCalculator;
import com.cryptopilot.market.calculator.SwingSupportResistance;
import com.cryptopilot.market.config.SetupScoreProperties;
import com.cryptopilot.market.entity.CryptoPair;
import com.cryptopilot.market.model.ComponentInputs;
import com.cryptopilot.market.model.ComponentScores;
import com.cryptopilot.market.model.ComponentWeights;
import com.cryptopilot.market.model.DerivativesInputs;
import com.cryptopilot.market.model.IndicatorSnapshot;
import com.cryptopilot.market.model.SetupScore;
import com.cryptopilot.market.model.SetupScoreTuning;
import com.cryptopilot.market.model.StoredCandle;
import com.cryptopilot.market.model.StylePreset;
import com.cryptopilot.market.model.enums.IndicatorOutcome;
import com.cryptopilot.market.model.enums.MarketInterval;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.model.enums.ScoredSide;
import com.cryptopilot.market.model.enums.SetupStyle;
import com.cryptopilot.market.repository.CryptoPairRepository;
import com.cryptopilot.market.repository.MarketDataQueryRepository;
import com.cryptopilot.market.repository.OhlcvRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Recomputes the v1 and v2 setup scores offline over the latest stored closed candles of some pairs, on each preset's
 * own timeframe and both markets, and writes their distribution to {@code calibration/}. Development only: active
 * under the {@code calibration} profile, which no deployment sets. Nothing is stored: the database is read in a
 * read-only transaction, so a write fails instead of reaching a table. The process then exits with status 0 once the
 * report is written, 1 when the run fails.
 *
 * <p>Arguments: {@code --pairs=BTCUSDT,ETHUSDT} (required), {@code --candles=1000}, {@code --out=calibration}.
 *
 * <p>Rule: BR-13; TECHNICAL_DESIGN 7.4.
 */
@Component
@Profile("calibration")
class CalibrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CalibrationRunner.class);

    static final int DEFAULT_CANDLES = 1000;
    static final int WARM_UP = 300;
    static final int SWING_CANDLES = 200;
    static final int FORWARD = 3;

    private final CryptoPairRepository pairs;
    private final OhlcvRepository candles;
    private final MarketDataQueryRepository marketData;
    private final SetupScoreProperties presets;
    private final Clock clock;
    private final TransactionTemplate readOnly;
    private final IntConsumer exit;

    @Autowired
    CalibrationRunner(
            CryptoPairRepository pairs,
            OhlcvRepository candles,
            MarketDataQueryRepository marketData,
            SetupScoreProperties presets,
            Clock clock,
            PlatformTransactionManager transactions,
            ConfigurableApplicationContext context) {
        this(
                pairs,
                candles,
                marketData,
                presets,
                clock,
                transactions,
                status -> System.exit(SpringApplication.exit(context, () -> status)));
    }

    /** With {@code exit} called with the exit status in place of ending the process, for tests. */
    CalibrationRunner(
            CryptoPairRepository pairs,
            OhlcvRepository candles,
            MarketDataQueryRepository marketData,
            SetupScoreProperties presets,
            Clock clock,
            PlatformTransactionManager transactions,
            IntConsumer exit) {
        this.pairs = pairs;
        this.candles = candles;
        this.marketData = marketData;
        this.presets = presets;
        this.clock = clock;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.exit = exit;
    }

    @Override
    public void run(ApplicationArguments args) {
        int status;
        try {
            int written = readOnly.execute(tx -> calibrate(args));
            log.info("Calibration: {} samples written", written);
            status = 0;
        } catch (RuntimeException failure) {
            log.error("Calibration failed", failure);
            status = 1;
        }
        exit.accept(status);
    }

    /** Reads the series, writes the report and returns the number of samples. */
    private int calibrate(ApplicationArguments args) {
        List<String> symbols = option(args, "pairs")
                .map(value -> Arrays.stream(value.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList())
                .orElseThrow(() -> new IllegalArgumentException("--pairs=BTCUSDT,ETHUSDT is required"));
        int n = option(args, "candles").map(Integer::parseInt).orElse(DEFAULT_CANDLES);
        Path out = Path.of(option(args, "out").orElse("calibration"));
        List<CalibrationReport.Sample> samples = new ArrayList<>();
        for (String symbol : symbols) {
            Optional<CryptoPair> pair = pairs.findBySymbol(symbol.toUpperCase());
            if (pair.isEmpty()) {
                log.warn("Calibration: no pair {}", symbol);
                continue;
            }
            for (MarketType market : MarketType.values()) {
                for (SetupStyle style : SetupStyle.values()) {
                    samples.addAll(series(pair.get(), market, presets.preset(style), n));
                }
            }
        }
        try {
            CalibrationReport.write(samples, out);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        log.info("Calibration: report written to {}", out.toAbsolutePath());
        return samples.size();
    }

    private List<CalibrationReport.Sample> series(CryptoPair pair, MarketType market, StylePreset preset, int n) {
        String tf = preset.timeframe();
        Duration interval = MarketInterval.fromCode(tf).orElseThrow().duration();
        List<StoredCandle> history =
                candles.closedCandles(pair.getId(), market, tf, null, clock.instant(), n + WARM_UP);
        SetupScoreTuning tuning = presets.v2();
        List<CalibrationReport.Sample> samples = new ArrayList<>();
        IndicatorEngine engine = new IndicatorEngine(interval);
        int first = Math.max(0, history.size() - n);
        for (int i = 0; i < history.size(); i++) {
            StoredCandle c = history.get(i);
            if (engine.offer(
                            c.openTime(),
                            c.close().doubleValue(),
                            c.baseVolume().doubleValue())
                    == IndicatorOutcome.GAP) {
                engine = new IndicatorEngine(interval);
                engine.offer(
                        c.openTime(), c.close().doubleValue(), c.baseVolume().doubleValue());
            }
            if (i < first) {
                continue;
            }
            IndicatorSnapshot snapshot = engine.snapshot();
            IndicatorSnapshot previous = engine.previousSnapshot();
            SwingSupportResistance.Levels levels =
                    SwingSupportResistance.nearest(history.subList(Math.max(0, i - SWING_CANDLES + 1), i + 1));
            DerivativesInputs derivatives =
                    market == MarketType.FUTURES ? marketData.derivativesAt(pair.getId(), c.closeTime()) : null;
            ComponentInputs in = new ComponentInputs(
                    market,
                    tf,
                    c.open(),
                    c.close(),
                    c.baseVolume(),
                    snapshot,
                    previous == null ? null : previous.macdHistogram(),
                    levels.support(),
                    levels.resistance(),
                    derivatives);
            ComponentScores v2 = SetupComponents.compute(in, tuning);
            if (v2.scoredSide() == null) {
                continue;
            }
            boolean hasDerivatives = market == MarketType.SPOT || v2.derivatives() != null;
            Scored v1Score = score(preset, V1SetupComponents.compute(in), hasDerivatives, tuning);
            Scored v2Score = score(preset, v2, hasDerivatives, tuning);
            if (v1Score == null || v2Score == null) {
                continue;
            }
            samples.add(new CalibrationReport.Sample(
                    pair.getSymbol(),
                    market,
                    tf,
                    preset.style(),
                    c.openTime(),
                    v2.scoredSide(),
                    v1Score.score(),
                    v2Score.score(),
                    v2Score.lowRoom(),
                    market == MarketType.FUTURES && v2.derivatives() != null,
                    forwardReturn(history, i, interval, v2.scoredSide())));
        }
        log.info("Calibration: {} {} {} {}: {} samples", pair.getSymbol(), market, tf, preset.style(), samples.size());
        return samples;
    }

    /**
     * The setup score of {@code c}: the calculator's, or on Futures without derivatives data the sum of trend, momentum
     * and level over their own weights, with the no-room cap on v2 components; {@code null} when a component is missing.
     */
    static Scored score(StylePreset preset, ComponentScores c, boolean hasDerivatives, SetupScoreTuning tuning) {
        if (hasDerivatives) {
            SetupScore s = SetupScoreCalculator.score(preset, c, null, tuning);
            return s.score() == null ? null : new Scored(s.score(), s.capReason() != null);
        }
        if (c.trend() == null || c.momentum() == null || c.level() == null) {
            return null;
        }
        ComponentWeights w = preset.weights(c.market());
        BigDecimal sum = c.trend()
                .multiply(BigDecimal.valueOf(w.trend()))
                .add(c.momentum().multiply(BigDecimal.valueOf(w.momentum())))
                .add(c.level().multiply(BigDecimal.valueOf(w.level())));
        BigDecimal uncapped = Rounding.divide(sum, BigDecimal.valueOf(w.trend() + w.momentum() + w.level()));
        BigDecimal capped = SetupComponents.FORMULA_VERSION.equals(c.formulaVersion())
                ? uncapped.min(SetupScoreCalculator.roomCap(c.level(), tuning))
                : uncapped;
        int score = rounded(capped);
        return new Scored(score, score < rounded(uncapped));
    }

    private static int rounded(BigDecimal score) {
        return score.setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    /** A score and whether the no-room cap lowered it. */
    record Scored(int score, boolean lowRoom) {}

    /** The close-to-close return over the next {@link #FORWARD} candles in the side's direction, if they are stored. */
    static Double forwardReturn(List<StoredCandle> history, int i, Duration interval, ScoredSide side) {
        if (i + FORWARD >= history.size()) {
            return null;
        }
        StoredCandle now = history.get(i);
        StoredCandle later = history.get(i + FORWARD);
        if (!later.openTime().equals(now.openTime().plus(interval.multipliedBy(FORWARD)))) {
            return null;
        }
        double change = later.close().doubleValue() / now.close().doubleValue() - 1;
        return side.sign() * change;
    }

    private static Optional<String> option(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
    }
}
