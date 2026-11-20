package com.cryptopilot.notification.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.event.MarketFallbackUnavailable;
import com.cryptopilot.market.event.MarketPauseAnnounced;
import com.cryptopilot.market.event.MarketRecoveryOverdue;
import com.cryptopilot.market.event.MarketTradingResumed;
import com.cryptopilot.market.model.enums.IncidentReason;
import com.cryptopilot.market.model.enums.MarketHealthState;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.notification.client.EmailMessage;
import com.cryptopilot.notification.client.EmailSender;
import com.cryptopilot.notification.config.MailDeliveryProperties;
import com.cryptopilot.notification.model.NewNotification;
import com.cryptopilot.notification.model.enums.NotificationType;
import com.cryptopilot.notification.repository.NotificationRepository;
import com.cryptopilot.paper.event.PaperIncidentNotice;
import com.cryptopilot.trading.event.PlanIncidentNotice;
import com.cryptopilot.user.UserApi;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The texts and keys of a market incident's notices (D-159): one pause notice per Trader and incident whichever module
 * asks, a reopening notice per module, the catch-up's counts named, and the administrators told of the pause.
 *
 * <p>Rule: NSF-03; NSF-15; D-159.
 */
class MarketNotificationServiceImplTest {

    private static final Instant LIVE = Instant.parse("2026-11-19T07:32:00Z");
    private static final Instant RESUMED = Instant.parse("2026-11-19T07:49:00Z");
    private static final UUID INCIDENT = UUID.randomUUID();
    private static final UUID TRADER = UUID.randomUUID();

    private final NotificationWriter writer = mock(NotificationWriter.class);
    private final UserApi users = mock(UserApi.class);
    private final EmailSender sender = mock(EmailSender.class);
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final MarketNotificationServiceImpl service = new MarketNotificationServiceImpl(
            writer,
            users,
            new MarketMails(new MailDeliveryProperties("no-reply@cryptopilot.test", URI.create("https://app.test"))),
            sender,
            notifications);

    /** N1, N6: a Trader the incident affects is mailed each notice once, when it is first written. */
    @Test
    void D159_anAffectedTrader_isMailedEachNotice_onlyWhenItIsFirstWritten() {
        when(writer.write(any())).thenReturn(true, false, true);
        when(users.notificationMailAddress(TRADER)).thenReturn(Optional.of("trader@example.test"));

        service.paperIncident(paper(PaperIncidentNotice.Kind.PAUSED, 0, 0, null));
        service.planIncident(
                new PlanIncidentNotice(TRADER, MarketType.FUTURES, INCIDENT, false, LIVE, null, null, false));
        service.paperIncident(paper(PaperIncidentNotice.Kind.RESUMED, 2, 0, null));

        ArgumentCaptor<EmailMessage> mailed = ArgumentCaptor.forClass(EmailMessage.class);
        verify(sender, times(2)).send(mailed.capture());
        assertThat(mailed.getAllValues())
                .extracting(EmailMessage::subject)
                .containsExactly("CryptoPilot: Futures trading paused", "CryptoPilot: Futures trading resumed");
        assertThat(mailed.getValue().to()).isEqualTo("trader@example.test");
        assertThat(mailed.getValue().text())
                .contains("2 fills of your orders")
                .contains("https://app.test/trade/futures");
        assertThat(mailed.getValue().html()).contains("Open the trading screen");
    }

    /** A mail server that refuses the mail loses the mail only: the notice stands and nothing is thrown. */
    @Test
    void D159_aRefusedMail_neverLosesTheNotice() {
        when(writer.write(any())).thenReturn(true);
        when(users.notificationMailAddress(TRADER)).thenReturn(Optional.of("trader@example.test"));
        org.mockito.Mockito.doThrow(new IllegalStateException("535 authentication failed"))
                .when(sender)
                .send(any());

        service.paperIncident(paper(PaperIncidentNotice.Kind.PAUSED, 0, 0, null));

        written(1);
        verify(sender).send(any());
    }

    /** N4: a Trader whose account takes no notification mail still gets the notice in the bell. */
    @Test
    void D159_aTraderWithoutNotificationMail_getsTheNoticeOnly() {
        when(writer.write(any())).thenReturn(true);
        when(users.notificationMailAddress(TRADER)).thenReturn(Optional.empty());

        service.planIncident(
                new PlanIncidentNotice(TRADER, MarketType.SPOT, INCIDENT, true, LIVE, RESUMED, null, false));

        written(1);
        verify(sender, never()).send(any());
    }

    /**
     * N2, N3: every other active Trader gets one general notice in the bell, and no mail; one told by a notice of their
     * own is left out.
     */
    @Test
    void D159_everyOtherTrader_getsOneGeneralNoticeInTheBell_andNoMail() {
        UUID other = UUID.randomUUID();
        when(users.activeTraders(null, MarketNotificationServiceImpl.TRADERS_PAGE))
                .thenReturn(List.of(TRADER, other));
        when(notifications.sourceKeysStartingWith("MARKET_PAUSED:" + INCIDENT + ":"))
                .thenReturn(List.of("MARKET_PAUSED:" + INCIDENT + ":" + TRADER));
        when(notifications.sourceKeysStartingWith("MARKET_RESUMED:" + INCIDENT + ":"))
                .thenReturn(List.of("MARKET_RESUMED:" + INCIDENT + ":" + TRADER + ":PLANS"));
        when(writer.write(any())).thenReturn(true);

        assertThat(service.broadcastPause(new MarketPauseAnnounced(
                        MarketType.SPOT, INCIDENT, MarketHealthState.PAUSED, IncidentReason.STREAM_LOST, LIVE, null)))
                .isOne();
        assertThat(service.broadcastResume(new MarketTradingResumed(MarketType.SPOT, INCIDENT, LIVE, RESUMED, true)))
                .isOne();

        List<NewNotification> written = written(2);
        assertThat(written).extracting(NewNotification::recipientId).containsOnly(other);
        assertThat(written)
                .extracting(NewNotification::sourceKey)
                .containsExactly(
                        "MARKET_PAUSED_ALL:" + INCIDENT + ":" + other, "MARKET_RESUMED_ALL:" + INCIDENT + ":" + other);
        assertThat(written.getFirst().content()).contains("Spot trading is paused");
        assertThat(written.getLast().content()).contains("from 07:32 UTC to 07:49 UTC");
        verify(sender, never()).send(any());
    }

    /** The Traders are read page by page until a page is not full. */
    @Test
    void D159_theGeneralNotice_walksTheTradersPageByPage() {
        List<UUID> full = java.util.stream.Stream.generate(UUID::randomUUID)
                .limit(MarketNotificationServiceImpl.TRADERS_PAGE)
                .toList();
        UUID last = UUID.randomUUID();
        when(users.activeTraders(null, MarketNotificationServiceImpl.TRADERS_PAGE))
                .thenReturn(full);
        when(users.activeTraders(full.getLast(), MarketNotificationServiceImpl.TRADERS_PAGE))
                .thenReturn(List.of(last));
        when(writer.write(any())).thenReturn(true);

        assertThat(service.broadcastResume(new MarketTradingResumed(MarketType.SPOT, INCIDENT, LIVE, RESUMED, true)))
                .isEqualTo(MarketNotificationServiceImpl.TRADERS_PAGE + 1);
    }

    @Test
    void D159_theGeneralNoticeOfMaintenance_quotesTheMessage() {
        when(users.activeTraders(null, MarketNotificationServiceImpl.TRADERS_PAGE))
                .thenReturn(List.of(TRADER));
        when(writer.write(any())).thenReturn(false);

        assertThat(service.broadcastPause(new MarketPauseAnnounced(
                        MarketType.FUTURES,
                        INCIDENT,
                        MarketHealthState.MAINTENANCE,
                        IncidentReason.MAINTENANCE,
                        LIVE,
                        "Back at 22:00.")))
                .as("already written")
                .isZero();
        assertThat(written(1).getFirst().content()).isEqualTo("Futures trading is under maintenance: Back at 22:00.");
    }

    @Test
    void D159_thePauseNotice_isOnePerTraderAndIncident_whicheverModuleAsks() {
        service.paperIncident(paper(PaperIncidentNotice.Kind.PAUSED, 0, 0, null));
        service.planIncident(
                new PlanIncidentNotice(TRADER, MarketType.FUTURES, INCIDENT, false, LIVE, null, null, false));

        List<NewNotification> written = written(2);
        assertThat(written)
                .extracting(NewNotification::sourceKey)
                .containsOnly("MARKET_PAUSED:" + INCIDENT + ":" + TRADER);
        assertThat(written.getFirst().type()).isEqualTo(NotificationType.MARKET);
        assertThat(written.getFirst().title()).isEqualTo("Futures trading paused");
        assertThat(written.getFirst().content())
                .contains("unavailable since 07:32 UTC")
                .contains("nothing is filled, liquidated or charged funding");
    }

    @Test
    void D159_aMaintenancePause_quotesTheAdministratorsMessage() {
        service.paperIncident(paper(PaperIncidentNotice.Kind.PAUSED, 0, 0, "Back at 22:00."));

        assertThat(written(1).getFirst().content()).startsWith("Futures trading is under maintenance: Back at 22:00.");
    }

    /** A maintenance without a message is still told as maintenance, to the affected Traders and to every other one. */
    @Test
    void D159_aMaintenanceWithoutAMessage_isToldAsMaintenance() {
        when(users.activeTraders(null, MarketNotificationServiceImpl.TRADERS_PAGE))
                .thenReturn(List.of(TRADER));
        service.paperIncident(new PaperIncidentNotice(
                TRADER, MarketType.FUTURES, INCIDENT, PaperIncidentNotice.Kind.PAUSED, LIVE, null, null, true, 0, 0));
        service.planIncident(new PlanIncidentNotice(TRADER, MarketType.SPOT, INCIDENT, false, LIVE, null, null, true));
        service.broadcastPause(new MarketPauseAnnounced(
                MarketType.FUTURES, INCIDENT, MarketHealthState.MAINTENANCE, IncidentReason.MAINTENANCE, LIVE, null));

        assertThat(written(3))
                .extracting(NewNotification::content)
                .allSatisfy(content -> assertThat(content).doesNotContain("unavailable"))
                .containsExactly(
                        "Futures trading is under maintenance. Your orders, positions and plans are kept as they are.",
                        "Spot trading is under maintenance. Your orders, positions and plans are kept as they are.",
                        "Futures trading is under maintenance.");
    }

    @Test
    void D159_theReopening_namesWhatTheCatchUpDid_orThatNothingChanged() {
        service.paperIncident(paper(PaperIncidentNotice.Kind.RESUMED, 2, 1, null));
        service.paperIncident(paper(PaperIncidentNotice.Kind.RESUMED, 0, 0, null));

        List<NewNotification> written = written(2);
        assertThat(written.get(0).title()).isEqualTo("Futures trading resumed");
        assertThat(written.get(0).content())
                .contains("from 07:32 UTC to 07:49 UTC")
                .contains("2 fills of your orders, and 1 position liquidated")
                .contains("Funding due in that period is settled at the exchange's rates.");
        assertThat(written.get(1).content()).contains("None of your orders or positions changed meanwhile.");
        assertThat(written.get(0).sourceKey()).isEqualTo("MARKET_RESUMED:" + INCIDENT + ":" + TRADER + ":PAPER");
    }

    @Test
    void D159_thePlansReopening_isAnotherNotice() {
        service.planIncident(
                new PlanIncidentNotice(TRADER, MarketType.SPOT, INCIDENT, true, LIVE, RESUMED, null, false));

        NewNotification written = written(1).getFirst();
        assertThat(written.title()).isEqualTo("Plans on Spot watched again");
        assertThat(written.sourceKey()).isEqualTo("MARKET_RESUMED:" + INCIDENT + ":" + TRADER + ":PLANS");
    }

    @Test
    void D159_everyActiveAdministrator_isToldOfThePause() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(users.activeAdministrators()).thenReturn(List.of(first, second));

        service.pauseAnnounced(new MarketPauseAnnounced(
                MarketType.SPOT, INCIDENT, MarketHealthState.PAUSED, IncidentReason.IP_BANNED, LIVE, null));

        List<NewNotification> written = written(2);
        assertThat(written).extracting(NewNotification::recipientId).containsExactly(first, second);
        assertThat(written).allMatch(notice -> notice.type() == NotificationType.SYSTEM);
        assertThat(written.getFirst().content()).contains("the exchange banned the server's address");
    }

    /** D-159: a recovery that needs an administrator is told to each active one, saying why. */
    @Test
    void D159_anOverdueRecovery_isToldToEveryAdministrator() {
        UUID first = UUID.randomUUID();
        when(users.activeAdministrators()).thenReturn(List.of(first));

        service.recoveryOverdue(new MarketRecoveryOverdue(MarketType.FUTURES, INCIDENT, LIVE, RESUMED, true));
        service.recoveryOverdue(new MarketRecoveryOverdue(MarketType.SPOT, INCIDENT, LIVE, RESUMED, false));

        List<NewNotification> written = written(2);
        assertThat(written).allMatch(notice -> notice.type() == NotificationType.SYSTEM);
        assertThat(written.getFirst().content()).contains("reopened by hand");
        assertThat(written.getFirst().sourceKey()).isEqualTo("MARKET_OVERDUE_ADMIN:" + INCIDENT + ":" + first);
        assertThat(written.getLast().content()).contains("still catching up");
    }

    /** E9: a lost market whose fallback prices cannot be read either is told to each active administrator. */
    @Test
    void D159_anUnavailableFallback_isToldToEveryAdministrator() {
        UUID admin = UUID.randomUUID();
        when(users.activeAdministrators()).thenReturn(List.of(admin));

        service.fallbackUnavailable(new MarketFallbackUnavailable(MarketType.SPOT, INCIDENT, "OKX answered 503"));

        NewNotification written = written(1).getFirst();
        assertThat(written.type()).isEqualTo(NotificationType.SYSTEM);
        assertThat(written.content()).contains("OKX answered 503");
        assertThat(written.sourceKey()).isEqualTo("MARKET_FALLBACK_ADMIN:" + INCIDENT + ":" + admin);
    }

    private PaperIncidentNotice paper(PaperIncidentNotice.Kind kind, long fills, long liquidations, String message) {
        return new PaperIncidentNotice(
                TRADER,
                MarketType.FUTURES,
                INCIDENT,
                kind,
                LIVE,
                kind == PaperIncidentNotice.Kind.RESUMED ? RESUMED : null,
                message,
                message != null,
                fills,
                liquidations);
    }

    private List<NewNotification> written(int times) {
        ArgumentCaptor<NewNotification> captor = ArgumentCaptor.forClass(NewNotification.class);
        verify(writer, times(times)).write(captor.capture());
        return captor.getAllValues();
    }
}
