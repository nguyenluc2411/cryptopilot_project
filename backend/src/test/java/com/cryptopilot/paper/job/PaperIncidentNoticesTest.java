package com.cryptopilot.paper.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.event.MarketPauseAnnounced;
import com.cryptopilot.market.event.MarketTradingResumed;
import com.cryptopilot.market.model.enums.IncidentReason;
import com.cryptopilot.market.model.enums.MarketHealthState;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.event.PaperIncidentNotice;
import com.cryptopilot.paper.service.PaperIncidentService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Who of the paper Traders hears of a market incident (D-159): on an announced pause, everyone with something waiting
 * on the market; on the reopening of an announced incident, the same and whoever the catch-up changed something for,
 * with their counts; on the reopening of an incident never announced, only the latter.
 *
 * <p>Rule: NSF-03; D-159.
 */
class PaperIncidentNoticesTest {

    private static final Instant LIVE = Instant.parse("2026-11-19T07:30:00Z");
    private static final Instant RESUMED = Instant.parse("2026-11-19T07:50:00Z");
    private static final UUID INCIDENT = UUID.randomUUID();
    private static final UUID WAITING = UUID.randomUUID();
    private static final UUID FILLED = UUID.randomUUID();
    private static final UUID LIQUIDATED = UUID.randomUUID();

    private final PaperIncidentService queries = mock(PaperIncidentService.class);
    private final List<PaperIncidentNotice> published = new ArrayList<>();
    private final PaperIncidentNotices notices =
            new PaperIncidentNotices(queries, event -> published.add((PaperIncidentNotice) event));

    @Test
    void D159_anAnnouncedPause_reachesEveryTraderWithSomethingWaiting() {
        when(queries.tradersOn(MarketType.FUTURES)).thenReturn(List.of(WAITING, FILLED));

        notices.on(new MarketPauseAnnounced(
                MarketType.FUTURES,
                INCIDENT,
                MarketHealthState.MAINTENANCE,
                IncidentReason.MAINTENANCE,
                LIVE,
                "deploy"));

        assertThat(published).extracting(PaperIncidentNotice::userId).containsExactly(WAITING, FILLED);
        assertThat(published).allSatisfy(notice -> {
            assertThat(notice.kind()).isEqualTo(PaperIncidentNotice.Kind.PAUSED);
            assertThat(notice.message()).isEqualTo("deploy");
            assertThat(notice.incidentId()).isEqualTo(INCIDENT);
        });
    }

    @Test
    void D159_theReopeningOfAnAnnouncedIncident_tellsEveryone_withWhatTheCatchUpDid() {
        when(queries.tradersOn(MarketType.FUTURES)).thenReturn(List.of(WAITING, FILLED));
        when(queries.replayedFills(MarketType.FUTURES, LIVE, RESUMED)).thenReturn(Map.of(FILLED, 2L));
        when(queries.liquidations(LIVE, RESUMED)).thenReturn(Map.of(LIQUIDATED, 1L));

        notices.on(new MarketTradingResumed(MarketType.FUTURES, INCIDENT, LIVE, RESUMED, true));

        assertThat(published)
                .extracting(PaperIncidentNotice::userId)
                .containsExactlyInAnyOrder(WAITING, FILLED, LIQUIDATED);
        assertThat(published).allMatch(notice -> notice.kind() == PaperIncidentNotice.Kind.RESUMED);
        assertThat(notice(FILLED).replayedFills()).isEqualTo(2);
        assertThat(notice(LIQUIDATED).liquidations()).isEqualTo(1);
        assertThat(notice(WAITING).replayedFills()).isZero();
        assertThat(notice(WAITING).resumedAt()).isEqualTo(RESUMED);
    }

    @Test
    void D159_theReopeningOfAShortIncident_tellsOnlyWhomTheCatchUpChanged_andSpotHasNoLiquidations() {
        when(queries.replayedFills(MarketType.SPOT, LIVE, RESUMED)).thenReturn(Map.of(FILLED, 1L));

        notices.on(new MarketTradingResumed(MarketType.SPOT, INCIDENT, LIVE, RESUMED, false));

        assertThat(published).extracting(PaperIncidentNotice::userId).containsExactly(FILLED);
        verify(queries, never()).tradersOn(any());
        verify(queries, never()).liquidations(any(), any());
    }

    private PaperIncidentNotice notice(UUID trader) {
        return published.stream()
                .filter(notice -> notice.userId().equals(trader))
                .findFirst()
                .orElseThrow();
    }
}
