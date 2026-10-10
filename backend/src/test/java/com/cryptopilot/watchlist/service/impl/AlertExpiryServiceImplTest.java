package com.cryptopilot.watchlist.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.watchlist.model.AlertsChanged;
import com.cryptopilot.watchlist.repository.AlertRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Which alerts a sweep ends and what it tells the engine: the alerts due at the clock's instant are expired, and only
 * those are announced with {@link AlertsChanged}. The repository is a test double; its queries are tested against
 * the database in {@link AlertTriggerServiceImplTest}.
 *
 * <p>Rule: BR-19; SRS 3.4.4; D-89.
 *
 * <p>Reference: Meszaros, G. (2007). <i>xUnit Test Patterns</i>. Addison-Wesley (Test Double, Test Spy).
 */
class AlertExpiryServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");

    private final AlertRepository alerts = mock(AlertRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final AlertExpiryServiceImpl service =
            new AlertExpiryServiceImpl(alerts, new MutableTestClock(NOW), events);

    @Test
    void BR19_nothingDue_expiresNothing_andAnnouncesNothing() {
        when(alerts.lockDueForExpiry(NOW)).thenReturn(List.of());

        assertThat(service.expireDue()).isZero();

        verify(alerts, never()).expire(anyCollection(), any());
        verifyNoInteractions(events);
    }

    @Test
    void BR19_theAlertsDueAtTheClocksInstant_areExpired_andAnnouncedToTheEngine() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(alerts.lockDueForExpiry(NOW)).thenReturn(List.of(first, second));
        when(alerts.expire(List.of(first, second), NOW)).thenReturn(2);

        assertThat(service.expireDue()).isEqualTo(2);

        verify(alerts).expire(List.of(first, second), NOW);
        verify(events).publishEvent(new AlertsChanged(Set.of(first, second)));
    }
}
