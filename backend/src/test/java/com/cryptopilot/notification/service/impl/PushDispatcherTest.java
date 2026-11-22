package com.cryptopilot.notification.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.model.enums.Feature;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.notification.client.PushMessage;
import com.cryptopilot.notification.client.PushResult;
import com.cryptopilot.notification.client.PushSender;
import com.cryptopilot.notification.config.PushProperties;
import com.cryptopilot.notification.model.enums.NotificationReference;
import com.cryptopilot.notification.model.enums.NotificationType;
import com.cryptopilot.notification.model.enums.PushOutcome;
import com.cryptopilot.user.UserApi;
import com.cryptopilot.watchlist.event.AlertTriggered;
import com.cryptopilot.watchlist.model.enums.AlertType;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The push dispatch of NSF-15 (T-058): who is pushed, what each answer of the service leads to, and the retries.
 *
 * <p>Invariants: (1) only the tokens the user module gives (active account, push on, active devices) are sent to; (2)
 * a delivered message is sent once; (3) an invalid token deactivates its device and is not sent again; (4) a failure is
 * sent {@code attempts} times in all, each wait twice the one before; (5) one device's failure does not stop the
 * others; (6) an alert is pushed only when it asked for a push and the plan has external alert channels; (7) a refusal
 * that is not retryable is sent once and keeps the device; (8) a {@code Retry-After} within the back-off bound is the
 * wait, one beyond it gives the device up.
 *
 * <p>Rule: NSF-15, BR-62.
 */
class PushDispatcherTest {

    private static final UUID TRADER = UUID.fromString("019b76da-a800-7000-8000-00000000e001");

    private final UserApi users = mock(UserApi.class);
    private final EntitlementApi entitlements = mock(EntitlementApi.class);
    private final PushSender sender = mock(PushSender.class);
    private final List<Duration> waits = new ArrayList<>();
    private final PushDispatcher dispatcher = new PushDispatcher(
            users,
            entitlements,
            sender,
            new PushProperties("", "", "https://fcm.example.test", 4, Duration.ofMillis(500), Duration.ofSeconds(1)),
            waits::add);

    @Test
    void NSF15_noActiveDevice_orPushSwitchedOff_sendsNothing() {
        when(users.pushTokens(TRADER)).thenReturn(List.of());

        assertThat(push()).isZero();
        verify(sender, never()).send(any());
    }

    @Test
    void NSF15_eachActiveDevice_isSentTheNotification_withItsData() {
        when(users.pushTokens(TRADER)).thenReturn(List.of("t1", "t2"));
        when(sender.send(any())).thenReturn(PushResult.of(PushOutcome.DELIVERED));

        assertThat(push()).isEqualTo(2);

        ArgumentCaptor<PushMessage> sent = ArgumentCaptor.forClass(PushMessage.class);
        verify(sender, times(2)).send(sent.capture());
        assertThat(sent.getAllValues()).extracting(PushMessage::token).containsExactly("t1", "t2");
        PushMessage first = sent.getAllValues().getFirst();
        assertThat(first.title()).isEqualTo("Order filled");
        assertThat(first.data())
                .containsEntry("type", "MARKET")
                .containsEntry("referenceType", "PAPER_ORDER")
                .containsKey("notificationId")
                .containsKey("referenceId");
        assertThat(waits).isEmpty();
    }

    @Test
    void NSF15_anInvalidToken_deactivatesItsDevice_andTheOthersStillGetIt() {
        when(users.pushTokens(TRADER)).thenReturn(List.of("stale", "fresh"));
        when(sender.send(any()))
                .thenAnswer(call -> ((PushMessage) call.getArgument(0)).token().equals("stale")
                        ? PushResult.of(PushOutcome.INVALID_TOKEN)
                        : PushResult.of(PushOutcome.DELIVERED));

        assertThat(push()).isEqualTo(1);

        verify(users).deactivateInvalidPushToken("stale");
        verify(users, never()).deactivateInvalidPushToken("fresh");
        verify(sender, times(2)).send(any());
    }

    @Test
    void NSF15_aFailedDelivery_isRetriedThreeTimes_withGrowingWaits_thenGivenUp() {
        when(users.pushTokens(TRADER)).thenReturn(List.of("t1"));
        when(sender.send(any())).thenReturn(PushResult.of(PushOutcome.FAILED));

        assertThat(push()).isZero();

        verify(sender, times(4)).send(any());
        assertThat(waits).containsExactly(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2));
        verify(users, never()).deactivateInvalidPushToken(any());
    }

    @Test
    void NSF15_aRefusalThatIsNotRetryable_isSentOnce_andKeepsTheDevice() {
        when(users.pushTokens(TRADER)).thenReturn(List.of("t1"));
        when(sender.send(any())).thenReturn(PushResult.of(PushOutcome.NON_RETRYABLE));

        assertThat(push()).isZero();

        verify(sender, times(1)).send(any());
        assertThat(waits).isEmpty();
        verify(users, never()).deactivateInvalidPushToken(any());
    }

    @Test
    void NSF15_aRetryAfterWithinTheBound_isTheWait() {
        when(users.pushTokens(TRADER)).thenReturn(List.of("t1"));
        when(sender.send(any()))
                .thenReturn(
                        new PushResult(PushOutcome.FAILED, Duration.ofMillis(200)),
                        new PushResult(PushOutcome.FAILED, Duration.ofMillis(1000)),
                        PushResult.of(PushOutcome.DELIVERED));

        assertThat(push()).isEqualTo(1);

        assertThat(waits).containsExactly(Duration.ofMillis(200), Duration.ofSeconds(1));
    }

    @Test
    void NSF15_aRetryAfterBeyondTheBound_givesTheDeviceUp() {
        when(users.pushTokens(TRADER)).thenReturn(List.of("t1"));
        when(sender.send(any())).thenReturn(new PushResult(PushOutcome.FAILED, Duration.ofSeconds(30)));

        assertThat(push()).isZero();

        verify(sender, times(1)).send(any());
        assertThat(waits).isEmpty();
        verify(users, never()).deactivateInvalidPushToken(any());
    }

    @Test
    void NSF15_aFailureThatRecovers_isDeliveredOnTheRetry() {
        when(users.pushTokens(TRADER)).thenReturn(List.of("t1"));
        when(sender.send(any())).thenReturn(PushResult.of(PushOutcome.FAILED), PushResult.of(PushOutcome.DELIVERED));

        assertThat(push()).isEqualTo(1);
        verify(sender, times(2)).send(any());
    }

    @Test
    void NSF15_anInterruptedWait_stopsTheRetries() {
        PushDispatcher interrupted = new PushDispatcher(
                users,
                entitlements,
                sender,
                new PushProperties("", "", "https://fcm.example.test", 4, Duration.ofMillis(1), Duration.ofSeconds(1)),
                ignored -> {
                    throw new InterruptedException();
                });
        when(users.pushTokens(TRADER)).thenReturn(List.of("t1"));
        when(sender.send(any())).thenReturn(PushResult.of(PushOutcome.FAILED));

        try {
            assertThat(interrupted.push(TRADER, null, NotificationType.SYSTEM, "t", "c", null, null))
                    .isZero();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        verify(sender, times(1)).send(any());
    }

    @Test
    void NSF15_anAlert_isPushedOnlyWhenItAskedForIt_andThePlanAllowsIt() {
        when(users.pushTokens(TRADER)).thenReturn(List.of("t1"));
        when(sender.send(any())).thenReturn(PushResult.of(PushOutcome.DELIVERED));
        when(entitlements.hasFeature(TRADER, Feature.EXTERNAL_ALERT_CHANNELS)).thenReturn(true, false);

        assertThat(dispatcher.pushAlert(alert(false))).as("no push wanted").isZero();
        assertThat(dispatcher.pushAlert(alert(true))).as("wanted, plan allows").isEqualTo(1);
        assertThat(dispatcher.pushAlert(alert(true))).as("wanted, plan lapsed").isZero();

        ArgumentCaptor<PushMessage> sent = ArgumentCaptor.forClass(PushMessage.class);
        verify(sender, times(1)).send(sent.capture());
        assertThat(sent.getValue().data())
                .containsEntry("type", "ALERT")
                .containsEntry("referenceType", "ALERT")
                .doesNotContainKey("notificationId");
    }

    private int push() {
        return dispatcher.push(
                TRADER,
                UUID.randomUUID(),
                NotificationType.MARKET,
                "Order filled",
                "Your BTCUSDT buy market order filled 2.",
                NotificationReference.PAPER_ORDER,
                UUID.randomUUID());
    }

    private static AlertTriggered alert(boolean notifyPush) {
        return new AlertTriggered(
                UUID.randomUUID(),
                TRADER,
                UUID.randomUUID(),
                "BTCUSDT",
                MarketType.SPOT,
                AlertType.PRICE,
                null,
                ConditionOperator.GREATER_THAN,
                new BigDecimal("100"),
                new BigDecimal("101"),
                Instant.parse("2026-11-22T08:00:00Z"),
                1,
                true,
                false,
                notifyPush);
    }
}
