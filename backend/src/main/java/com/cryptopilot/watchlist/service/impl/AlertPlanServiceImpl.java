package com.cryptopilot.watchlist.service.impl;

import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.event.PlanDowngraded;
import com.cryptopilot.billing.model.enums.Feature;
import com.cryptopilot.common.lock.UserLock;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.watchlist.entity.Alert;
import com.cryptopilot.watchlist.event.AlertsPausedForPlan;
import com.cryptopilot.watchlist.model.AlertsChanged;
import com.cryptopilot.watchlist.model.enums.AlertStatus;
import com.cryptopilot.watchlist.model.enums.AlertType;
import com.cryptopilot.watchlist.repository.AlertRepository;
import com.cryptopilot.watchlist.service.AlertPlanService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BR-64 for alerts, under the Trader's watchlist lock, the one every path that adds an ACTIVE alert takes, so no
 * alert is created or resumed between the count and the pauses. The plan is read when the drop is handled, not taken
 * from the event, so a plan bought again meanwhile is the one applied.
 *
 * <p>Rule: BR-64; BR-17; D-63.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AlertPlanServiceImpl implements AlertPlanService {

    private static final Logger log = LoggerFactory.getLogger(AlertPlanServiceImpl.class);

    private final AlertRepository alerts;
    private final UserLock lock;
    private final EntitlementApi entitlements;
    private final ApplicationEventPublisher events;

    @Override
    @Transactional
    public int applyPlan(PlanDowngraded downgrade) {
        UUID userId = downgrade.userId();
        lock.lock(userId);
        List<Alert> active = alerts.findByUserIdAndStatusOrderByCreatedAtDescIdDesc(userId, AlertStatus.ACTIVE);
        boolean indicators = entitlements.hasFeature(userId, Feature.INDICATOR_ALERT);
        boolean futures = entitlements.hasFeature(userId, Feature.FUTURES_ANALYSIS);
        Integer max = entitlements.getLimit(userId, Feature.ACTIVE_ALERT_MAX);
        List<Alert> kept = new ArrayList<>();
        Set<UUID> paused = new HashSet<>();
        for (Alert alert : active) {
            boolean allowed = (indicators || alert.getType() != AlertType.INDICATOR)
                    && (futures || alert.getMarket() != MarketType.FUTURES);
            // Newest first: the alerts kept within the limit are the most recently created (BR-64).
            if (allowed && (max == null || kept.size() < max)) {
                kept.add(alert);
            } else {
                alert.pause();
                paused.add(alert.getId());
            }
        }
        if (!paused.isEmpty()) {
            events.publishEvent(new AlertsChanged(Set.copyOf(paused)));
            log.info("paused {} alerts of {} after the plan dropped to {}", paused.size(), userId, downgrade.toTier());
        }
        events.publishEvent(new AlertsPausedForPlan(userId, downgrade.toPlanName(), paused.size(), downgrade.at()));
        return paused.size();
    }
}
