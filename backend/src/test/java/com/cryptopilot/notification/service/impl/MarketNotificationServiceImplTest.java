package com.cryptopilot.notification.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.event.MarketPauseAnnounced;
import com.cryptopilot.market.model.enums.IncidentReason;
import com.cryptopilot.market.model.enums.MarketHealthState;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.notification.model.NewNotification;
import com.cryptopilot.notification.model.enums.NotificationType;
import com.cryptopilot.paper.event.PaperIncidentNotice;
import com.cryptopilot.trading.event.PlanIncidentNotice;
import com.cryptopilot.user.UserApi;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The texts and keys of a market incident's notices (D-158): one pause notice per Trader and incident whichever module
 * asks, a reopening notice per module, the catch-up's counts named, and the administrators told of the pause.
 *
 * <p>Rule: NSF-03; NSF-15; D-158.
 */
class MarketNotificationServiceImplTest {

    private static final Instant LIVE = Instant.parse("2026-11-19T07:32:00Z");
    private static final Instant RESUMED = Instant.parse("2026-11-19T07:49:00Z");
    private static final UUID INCIDENT = UUID.randomUUID();
    private static final UUID TRADER = UUID.randomUUID();

    private final NotificationWriter writer = mock(NotificationWriter.class);
    private final UserApi users = mock(UserApi.class);
    private final MarketNotificationServiceImpl service = new MarketNotificationServiceImpl(writer, users);

    @Test
    void D158_thePauseNotice_isOnePerTraderAndIncident_whicheverModuleAsks() {
        service.paperIncident(paper(PaperIncidentNotice.Kind.PAUSED, 0, 0, null));
        service.planIncident(new PlanIncidentNotice(TRADER, MarketType.FUTURES, INCIDENT, false, LIVE, null, null));

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
    void D158_aMaintenancePause_quotesTheAdministratorsMessage() {
        service.paperIncident(paper(PaperIncidentNotice.Kind.PAUSED, 0, 0, "Back at 22:00."));

        assertThat(written(1).getFirst().content()).startsWith("Futures trading is under maintenance: Back at 22:00.");
    }

    @Test
    void D158_theReopening_namesWhatTheCatchUpDid_orThatNothingChanged() {
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
    void D158_thePlansReopening_isAnotherNotice() {
        service.planIncident(new PlanIncidentNotice(TRADER, MarketType.SPOT, INCIDENT, true, LIVE, RESUMED, null));

        NewNotification written = written(1).getFirst();
        assertThat(written.title()).isEqualTo("Plans on Spot watched again");
        assertThat(written.sourceKey()).isEqualTo("MARKET_RESUMED:" + INCIDENT + ":" + TRADER + ":PLANS");
    }

    @Test
    void D158_everyActiveAdministrator_isToldOfThePause() {
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

    private PaperIncidentNotice paper(PaperIncidentNotice.Kind kind, long fills, long liquidations, String message) {
        return new PaperIncidentNotice(
                TRADER,
                MarketType.FUTURES,
                INCIDENT,
                kind,
                LIVE,
                kind == PaperIncidentNotice.Kind.RESUMED ? RESUMED : null,
                message,
                fills,
                liquidations);
    }

    private List<NewNotification> written(int times) {
        ArgumentCaptor<NewNotification> captor = ArgumentCaptor.forClass(NewNotification.class);
        verify(writer, times(times)).write(captor.capture());
        return captor.getAllValues();
    }
}
