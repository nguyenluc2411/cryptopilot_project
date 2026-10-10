package com.cryptopilot.watchlist.service.impl;

import com.cryptopilot.watchlist.model.AlertsChanged;
import com.cryptopilot.watchlist.repository.AlertRepository;
import com.cryptopilot.watchlist.service.AlertExpiryService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expires the alerts past their expiry in one transaction: their rows are locked, set EXPIRED with a conditional
 * update, and {@link AlertsChanged} lets the engine drop them after the commit.
 *
 * <p>Rule: BR-19; SRS 3.4.4.
 *
 * <p>Reference: Kleppmann, M. (2017). <i>Designing Data-Intensive Applications</i>. O'Reilly, ch. 7 (a conditional
 * update on rows locked first, so a concurrent edit or trigger is not lost).
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AlertExpiryServiceImpl implements AlertExpiryService {

    private final AlertRepository alerts;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    @Override
    @Transactional
    public int expireDue() {
        Instant now = clock.instant();
        List<UUID> due = alerts.lockDueForExpiry(now);
        if (due.isEmpty()) {
            return 0;
        }
        int expired = alerts.expire(due, now);
        events.publishEvent(new AlertsChanged(Set.copyOf(due)));
        return expired;
    }
}
