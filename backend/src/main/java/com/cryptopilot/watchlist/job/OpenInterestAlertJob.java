package com.cryptopilot.watchlist.job;

import com.cryptopilot.market.event.OpenInterestSampled;
import com.cryptopilot.watchlist.config.AlertEngineProperties;
import com.cryptopilot.watchlist.service.OpenInterestAlertService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * NSF-06 for OPEN_INTEREST_CHANGE alerts: once a live open interest reading is committed, evaluates the alerts of its
 * pair on the NSF-04 thread. Runs where the alert engine runs; a failure is logged and does not reach NSF-04.
 *
 * <p>Rule: NSF-06; SRS 3.4.4; Q-45.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class OpenInterestAlertJob {

    private static final Logger log = LoggerFactory.getLogger(OpenInterestAlertJob.class);

    private final OpenInterestAlertService alerts;
    private final AlertEngineProperties properties;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOpenInterestSampled(OpenInterestSampled sample) {
        if (!properties.enabled()) {
            return;
        }
        try {
            alerts.evaluate(sample);
        } catch (RuntimeException failure) {
            log.warn(
                    "NSF-06 {}: OPEN_INTEREST_CHANGE alerts of the reading at {} not evaluated: {}",
                    sample.symbol(),
                    sample.sampleTime(),
                    failure.toString());
        }
    }
}
