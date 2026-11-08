package com.cryptopilot.watchlist.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/** BR-64 for alerts: what the lower plan does not allow is paused, newest kept within the limit, nothing deleted. */
class AlertPlanServiceImplTest {

    private static final UUID USER = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-12-08T03:00:00Z");
    private static final PlanDowngraded TO_FREE = new PlanDowngraded(USER, "PRO", "FREE", "Free", AT);

    private final AlertRepository alerts = mock(AlertRepository.class);
    private final UserLock lock = mock(UserLock.class);
    private final EntitlementApi entitlements = mock(EntitlementApi.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final AlertPlanServiceImpl service = new AlertPlanServiceImpl(alerts, lock, entitlements, events);

    @Test
    void BR64_aDropToFree_pausesIndicatorAndFuturesAlerts_thenTheOldestAboveTheLimit() {
        Alert newestPrice = alert(AlertType.PRICE, MarketType.SPOT);
        Alert indicator = alert(AlertType.INDICATOR, MarketType.SPOT);
        Alert futures = alert(AlertType.PRICE, MarketType.FUTURES);
        Alert secondPrice = alert(AlertType.PRICE, MarketType.SPOT);
        Alert oldestPrice = alert(AlertType.PRICE, MarketType.SPOT);
        when(alerts.findByUserIdAndStatusOrderByCreatedAtDescIdDesc(USER, AlertStatus.ACTIVE))
                .thenReturn(List.of(newestPrice, indicator, futures, secondPrice, oldestPrice));
        plan(false, false, 2);

        assertThat(service.applyPlan(TO_FREE)).isEqualTo(3);

        verify(lock).lock(USER);
        verify(newestPrice, never()).pause();
        verify(secondPrice, never()).pause();
        verify(indicator).pause();
        verify(futures).pause();
        verify(oldestPrice).pause();
        verify(events).publishEvent(new AlertsChanged(Set.of(indicator.getId(), futures.getId(), oldestPrice.getId())));
        verify(events).publishEvent(new AlertsPausedForPlan(USER, "Free", 3, AT));
    }

    @Test
    void BR64_aPlanThatStillAllowsEverything_pausesNothing_andStillTellsTheTrader() {
        Alert indicator = alert(AlertType.INDICATOR, MarketType.FUTURES);
        when(alerts.findByUserIdAndStatusOrderByCreatedAtDescIdDesc(USER, AlertStatus.ACTIVE))
                .thenReturn(List.of(indicator));
        plan(true, true, null);

        assertThat(service.applyPlan(TO_FREE)).isZero();

        verify(indicator, never()).pause();
        verify(events, never()).publishEvent(any(AlertsChanged.class));
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue()).isEqualTo(new AlertsPausedForPlan(USER, "Free", 0, AT));
    }

    private void plan(boolean indicators, boolean futures, Integer maxActive) {
        when(entitlements.hasFeature(USER, Feature.INDICATOR_ALERT)).thenReturn(indicators);
        when(entitlements.hasFeature(USER, Feature.FUTURES_ANALYSIS)).thenReturn(futures);
        when(entitlements.getLimit(USER, Feature.ACTIVE_ALERT_MAX)).thenReturn(maxActive);
    }

    private static Alert alert(AlertType type, MarketType market) {
        Alert alert = mock(Alert.class);
        when(alert.getId()).thenReturn(UUID.randomUUID());
        when(alert.getType()).thenReturn(type);
        when(alert.getMarket()).thenReturn(market);
        return alert;
    }
}
