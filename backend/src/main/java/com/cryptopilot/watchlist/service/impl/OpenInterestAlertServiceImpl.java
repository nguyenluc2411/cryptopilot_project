package com.cryptopilot.watchlist.service.impl;

import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.event.OpenInterestSampled;
import com.cryptopilot.watchlist.calculator.IndicatorCondition;
import com.cryptopilot.watchlist.calculator.OpenInterestChangeEvaluator;
import com.cryptopilot.watchlist.model.IndicatorAlert;
import com.cryptopilot.watchlist.model.IndicatorAlertHit;
import com.cryptopilot.watchlist.model.IndicatorReading;
import com.cryptopilot.watchlist.service.AlertTriggerService;
import com.cryptopilot.watchlist.service.OpenInterestAlertService;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Evaluates a live open interest reading against the ACTIVE OPEN_INTEREST_CHANGE alerts of its pair: the change is
 * read from the stored readings ({@link OpenInterestChangeEvaluator}), compared in percent with the Trader's level
 * like every alert (D-88), and each trigger is decided by its conditional update, at most once per reading (D-89).
 *
 * <p>Rule: NSF-06, BR-19, BR-20; SRS 3.4.4; D-88, D-89; Q-39, Q-41, Q-45.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class OpenInterestAlertServiceImpl implements OpenInterestAlertService {

    private static final Logger log = LoggerFactory.getLogger(OpenInterestAlertServiceImpl.class);

    private final AlertTriggerService triggers;
    private final MarketApi market;
    private final Clock clock;

    @Override
    public int evaluate(OpenInterestSampled sample) {
        List<IndicatorAlert> alerts = triggers.activeOpenInterestAlerts(sample.pairId());
        if (alerts.isEmpty()) {
            return 0;
        }
        Optional<IndicatorReading> reading = OpenInterestChangeEvaluator.read(
                sample.sampleTime(), at -> market.openInterestAtOrBefore(sample.pairId(), at));
        if (reading.isEmpty()) {
            return 0;
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int recorded = 0;
        for (IndicatorAlert alert : alerts) {
            if (IndicatorCondition.met(alert.condition(), alert.threshold(), reading.get())
                    && trigger(new IndicatorAlertHit(
                            alert, sample.symbol(), reading.get().current(), sample.sampleTime(), now))) {
                recorded++;
            }
        }
        return recorded;
    }

    private boolean trigger(IndicatorAlertHit hit) {
        try {
            return triggers.tryTriggerOpenInterest(hit);
        } catch (RuntimeException failure) {
            log.warn("NSF-06 alert {} trigger failed: {}", hit.alert().alertId(), failure.toString());
            return false;
        }
    }
}
