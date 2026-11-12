package com.cryptopilot.billing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.billing.config.SubscriptionSweepProperties;
import com.cryptopilot.billing.entity.UserSubscription;
import com.cryptopilot.billing.model.enums.SubscriptionStatus;
import com.cryptopilot.billing.repository.SubscriptionOrderRepository;
import com.cryptopilot.billing.repository.SubscriptionPackageRepository;
import com.cryptopilot.billing.repository.UserSubscriptionRepository;
import com.cryptopilot.common.lock.UserLock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The hourly run leaves alone what it must not touch (NSF-14): a subscription whose owner is gone, one a payment
 * replaced while the lock was awaited, one still running, one already reminded or already ended; and one failure does
 * not stop the others.
 */
class SubscriptionLifecycleDecisionsTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    private final UserSubscriptionRepository subscriptions = mock(UserSubscriptionRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private SubscriptionLifecycleServiceImpl service;

    @BeforeEach
    void setUp() {
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any()))
                .thenAnswer(call -> call.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        service = new SubscriptionLifecycleServiceImpl(
                subscriptions,
                mock(SubscriptionOrderRepository.class),
                mock(SubscriptionPackageRepository.class),
                mock(UserLock.class),
                transactions,
                events,
                new SubscriptionSweepProperties(true, "0 0 * * * *", Duration.ofDays(3), 50),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void NSF14_theExpiryRun_skipsWhatAPaymentChanged_whatStillRuns_andAFailure() {
        UUID gone = UUID.randomUUID();
        UUID replaced = UUID.randomUUID();
        UUID running = UUID.randomUUID();
        UUID broken = UUID.randomUUID();
        when(subscriptions.findEndedIds(any(), any())).thenReturn(List.of(gone, replaced, running, broken));
        when(subscriptions.findUserIdOf(gone)).thenReturn(Optional.empty());
        for (UUID id : List.of(replaced, running, broken)) {
            when(subscriptions.findUserIdOf(id)).thenReturn(Optional.of(UUID.randomUUID()));
        }
        UserSubscription replacedRow = subscription(SubscriptionStatus.REPLACED, NOW.minusSeconds(60));
        UserSubscription runningRow = subscription(SubscriptionStatus.ACTIVE, NOW.plusSeconds(60));
        when(subscriptions.findById(replaced)).thenReturn(Optional.of(replacedRow));
        when(subscriptions.findById(running)).thenReturn(Optional.of(runningRow));
        when(subscriptions.findById(broken)).thenThrow(new IllegalStateException("db down"));

        assertThat(service.expireEnded()).isZero();
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void NSF14_theReminder_skipsAReplacedAnEndedAndAnAlreadyRemindedSubscription() {
        UUID replaced = UUID.randomUUID();
        UUID ended = UUID.randomUUID();
        UUID reminded = UUID.randomUUID();
        when(subscriptions.findIdsToRemind(any(), any(), any())).thenReturn(List.of(replaced, ended, reminded));
        for (UUID id : List.of(replaced, ended, reminded)) {
            when(subscriptions.findUserIdOf(id)).thenReturn(Optional.of(UUID.randomUUID()));
        }
        UserSubscription replacedRow = subscription(SubscriptionStatus.REPLACED, NOW.plus(Duration.ofDays(1)));
        UserSubscription endedRow = subscription(SubscriptionStatus.ACTIVE, NOW);
        when(subscriptions.findById(replaced)).thenReturn(Optional.of(replacedRow));
        when(subscriptions.findById(ended)).thenReturn(Optional.of(endedRow));
        UserSubscription told = subscription(SubscriptionStatus.ACTIVE, NOW.plus(Duration.ofDays(1)));
        when(told.remind(NOW)).thenReturn(false);
        when(subscriptions.findById(reminded)).thenReturn(Optional.of(told));

        assertThat(service.remindEnding()).isZero();
        verify(events, never()).publishEvent(any(Object.class));
    }

    private static UserSubscription subscription(SubscriptionStatus status, Instant endAt) {
        UserSubscription subscription = mock(UserSubscription.class);
        when(subscription.getStatus()).thenReturn(status);
        when(subscription.getEndAt()).thenReturn(endAt);
        return subscription;
    }
}
