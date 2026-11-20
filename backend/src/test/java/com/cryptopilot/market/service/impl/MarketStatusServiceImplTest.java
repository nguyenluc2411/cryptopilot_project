package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cryptopilot.admin.AdminApi;
import com.cryptopilot.market.RecoveryProgress;
import com.cryptopilot.market.client.BinanceClientException;
import com.cryptopilot.market.client.BinanceRestClient;
import com.cryptopilot.market.client.RestOutage;
import com.cryptopilot.market.dto.response.MarketHealthResponse;
import com.cryptopilot.market.dto.response.MarketRecoveryResponse;
import com.cryptopilot.market.model.MarketIncident;
import com.cryptopilot.market.model.enums.BinanceVenue;
import com.cryptopilot.market.model.enums.DisplaySource;
import com.cryptopilot.market.model.enums.IncidentKind;
import com.cryptopilot.market.model.enums.IncidentReason;
import com.cryptopilot.market.model.enums.MarketHealthState;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.repository.MarketIncidentRepository;
import com.cryptopilot.market.service.DisplayPriceService;
import com.cryptopilot.market.service.MarketHealthService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the banner reads of each market (D-158): while a market recovers, how far it has caught up and when the
 * exchange takes the calls again; while it is open, the pairs whose data is lost (E5).
 *
 * <p>Rule: NSF-03; D-158.
 */
class MarketStatusServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-19T08:00:00Z");

    private final MarketHealthService health = mock(MarketHealthService.class);
    private final MarketIncidentRepository incidents = mock(MarketIncidentRepository.class);
    private final MarketLiveness liveness = mock(MarketLiveness.class);
    private final BinanceRestClient exchange = mock(BinanceRestClient.class);
    private final DisplayPriceService prices = mock(DisplayPriceService.class);
    private final MarketStatusServiceImpl service = new MarketStatusServiceImpl(
            health, incidents, mock(AdminApi.class), liveness, exchange, prices, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void spotLive_futuresRecovering() {
        when(health.state(MarketType.SPOT)).thenReturn(MarketHealthState.LIVE);
        when(health.state(MarketType.FUTURES)).thenReturn(MarketHealthState.RECOVERING);
        when(incidents.findRecent(MarketType.SPOT, 1)).thenReturn(List.of());
        when(incidents.findOpen(MarketType.FUTURES))
                .thenReturn(Optional.of(new MarketIncident(
                        UUID.randomUUID(),
                        MarketType.FUTURES,
                        IncidentKind.OUTAGE,
                        IncidentReason.IP_BANNED,
                        NOW.minusSeconds(600),
                        NOW.minusSeconds(570),
                        NOW.minusSeconds(60),
                        null,
                        null,
                        null)));
        when(health.recoveryProgress(MarketType.FUTURES)).thenReturn(Optional.of(new RecoveryProgress(3, 5)));
        when(exchange.outage(BinanceVenue.USD_M_FUTURES))
                .thenReturn(Optional.of(new RestOutage(BinanceClientException.Kind.BANNED, NOW.plusSeconds(300))));
        when(exchange.outage(BinanceVenue.SPOT)).thenReturn(Optional.empty());
        when(liveness.silentSymbols(MarketType.SPOT)).thenReturn(List.of("BTCUSDT"));
        when(prices.source(MarketType.FUTURES)).thenReturn(DisplaySource.OKX);
        when(health.dataLost(MarketType.FUTURES)).thenReturn(true);
    }

    @Test
    void D158_aRecoveringMarket_showsItsProgressAndWhenTheExchangeTakesCallsAgain() {
        MarketHealthResponse futures = service.status().markets().get(1);

        assertThat(futures.market()).isEqualTo(MarketType.FUTURES);
        assertThat(futures.recovery()).isEqualTo(new MarketRecoveryResponse(3, 5));
        assertThat(futures.retryAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(futures.reason()).isEqualTo(IncidentReason.IP_BANNED);
        assertThat(futures.pausedPairs()).isEmpty();
        assertThat(futures.displaySource()).isEqualTo(DisplaySource.OKX);
        assertThat(futures.dataLost()).isTrue();
    }

    @Test
    void D158_anOpenMarket_namesItsLostPairs_andNoRetryOrProgress() {
        MarketHealthResponse spot = service.status().markets().getFirst();

        assertThat(spot.tradingOpen()).isTrue();
        assertThat(spot.pausedPairs()).containsExactly("BTCUSDT");
        assertThat(spot.retryAt()).isNull();
        assertThat(spot.recovery()).isNull();
        assertThat(spot.displaySource()).isEqualTo(DisplaySource.BINANCE);
        assertThat(spot.dataLost()).isFalse();
    }
}
