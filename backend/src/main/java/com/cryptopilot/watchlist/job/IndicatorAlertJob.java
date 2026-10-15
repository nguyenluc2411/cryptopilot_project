package com.cryptopilot.watchlist.job;

import com.cryptopilot.market.event.IndicatorsComputed;
import com.cryptopilot.watchlist.config.AlertEngineProperties;
import com.cryptopilot.watchlist.service.IndicatorAlertService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * NSF-06 for indicator alerts: once the indicators of a closed candle are committed, evaluates the alerts of its
 * series. Runs where the alert engine runs ({@code cryptopilot.alert.enabled}); a failure is logged and does not reach
 * NSF-05.
 *
 * <p>Rule: NSF-06, BR-18; SRS 3.4.4; TECHNICAL_DESIGN 7.9.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class IndicatorAlertJob {

    private static final Logger log = LoggerFactory.getLogger(IndicatorAlertJob.class);

    private final IndicatorAlertService alerts;
    private final AlertEngineProperties properties;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIndicatorsComputed(IndicatorsComputed candle) {
        if (!properties.enabled()) {
            return;
        }
        try {
            alerts.evaluate(candle);
        } catch (RuntimeException failure) {
            log.warn(
                    "NSF-06 {} {} {}: indicator alerts of the candle opened at {} not evaluated: {}",
                    candle.symbol(),
                    candle.market(),
                    candle.timeframe(),
                    candle.openTime(),
                    failure.toString());
        }
    }
}
