package com.cryptopilot.watchlist.service.impl;

import com.cryptopilot.market.event.IndicatorsComputed;
import com.cryptopilot.watchlist.calculator.EmaCrossEvaluator;
import com.cryptopilot.watchlist.calculator.IndicatorCondition;
import com.cryptopilot.watchlist.calculator.IndicatorEvaluator;
import com.cryptopilot.watchlist.calculator.MacdCrossEvaluator;
import com.cryptopilot.watchlist.calculator.Rsi14Evaluator;
import com.cryptopilot.watchlist.model.IndicatorAlert;
import com.cryptopilot.watchlist.model.IndicatorAlertHit;
import com.cryptopilot.watchlist.model.IndicatorReading;
import com.cryptopilot.watchlist.model.enums.AlertIndicator;
import com.cryptopilot.watchlist.service.AlertTriggerService;
import com.cryptopilot.watchlist.service.IndicatorAlertService;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Evaluates a closed candle against the indicator alerts of its series: each alert's evaluator reads the value it
 * compares, {@link IndicatorCondition} decides the condition, and {@link AlertTriggerService#tryTriggerIndicator}
 * records the trigger, each in its own transaction, so one alert's failure leaves the others. The trigger mode and
 * cooldown are left to that conditional update (D-89), which also lets one candle fire an alert at most once.
 *
 * <p>Only closed candles reach here: NSF-05 computes indicators of closed candles alone (BR-08, BR-18).
 *
 * <p>Rule: NSF-06, BR-18, BR-19, BR-20; SRS 3.4.4; TECHNICAL_DESIGN 7.9; D-88, D-89.
 *
 * <p>Reference: Gamma, E., Helm, R., Johnson, R. &amp; Vlissides, J. (1994). <i>Design Patterns</i>. Addison-Wesley,
 * "Strategy" (one evaluator per indicator).
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class IndicatorAlertServiceImpl implements IndicatorAlertService {

    private static final Logger log = LoggerFactory.getLogger(IndicatorAlertServiceImpl.class);

    private static final Map<AlertIndicator, IndicatorEvaluator> EVALUATORS =
            evaluators(new Rsi14Evaluator(), new MacdCrossEvaluator(), new EmaCrossEvaluator());

    private final AlertTriggerService triggers;
    private final Clock clock;

    @Override
    public int evaluate(IndicatorsComputed candle) {
        List<IndicatorAlert> alerts =
                triggers.activeIndicatorAlerts(candle.pairId(), candle.market(), candle.timeframe());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int recorded = 0;
        for (IndicatorAlert alert : alerts) {
            Optional<IndicatorReading> reading =
                    Optional.ofNullable(EVALUATORS.get(alert.indicator())).flatMap(evaluator -> evaluator.read(candle));
            if (reading.isEmpty() || !IndicatorCondition.met(alert.condition(), alert.threshold(), reading.get())) {
                continue;
            }
            try {
                if (triggers.tryTriggerIndicator(new IndicatorAlertHit(
                        alert, candle.symbol(), reading.get().current(), candle.openTime(), now))) {
                    recorded++;
                }
            } catch (RuntimeException failure) {
                log.warn("NSF-06 alert {} trigger failed: {}", alert.alertId(), failure.toString());
            }
        }
        return recorded;
    }

    private static Map<AlertIndicator, IndicatorEvaluator> evaluators(IndicatorEvaluator... all) {
        Map<AlertIndicator, IndicatorEvaluator> byIndicator = new EnumMap<>(AlertIndicator.class);
        Stream.of(all).forEach(evaluator -> byIndicator.put(evaluator.indicator(), evaluator));
        return Map.copyOf(byIndicator);
    }
}
