package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.admin.AdminApi;
import com.cryptopilot.admin.AuditEntry;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.market.dto.request.ReplayStartRequest;
import com.cryptopilot.market.dto.request.ReplayStopRequest;
import com.cryptopilot.market.dto.request.StreamFaultRequest;
import com.cryptopilot.market.dto.response.ReplayResponse;
import com.cryptopilot.market.dto.response.StreamFaultResponse;
import com.cryptopilot.market.job.MarketStreamSupervisor;
import com.cryptopilot.market.job.PriceReplayPlayer;
import com.cryptopilot.market.model.ReplayScenario;
import com.cryptopilot.market.model.ReplayStatus;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.support.MutableTestClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * RF-07 and RF-12 below the web layer: the repository's scenarios load and are valid, a replay starts on the
 * scenario's pair and stops, a cut reaches the supervisor, and each change is audited.
 */
class MarketTestToolServiceImplTest {

    private static final Instant AT = Instant.parse("2026-11-27T10:00:00Z");
    private static final UUID ADMIN = UUID.randomUUID();

    private final MarketStreamSupervisor supervisor = mock(MarketStreamSupervisor.class);
    private final PriceReplayPlayer player = mock(PriceReplayPlayer.class);
    private final AdminApi admin = mock(AdminApi.class);
    private final MarketTestToolServiceImpl service = new MarketTestToolServiceImpl(
            supervisor,
            player,
            admin,
            new MutableTestClock(AT),
            JsonMapper.builder().build());

    /** Every scenario file of the repository loads, and the demonstrations' prices are as BM04 states them. */
    @Test
    void RF07_theRepositorysScenarios_loadWithTheDemonstrationsPrices() {
        Map<String, ReplayScenario> scenarios =
                MarketTestToolServiceImpl.load(JsonMapper.builder().build());

        assertThat(scenarios)
                .containsOnlyKeys("btc_hold_60000", "btc_liq", "btc_near_liq", "eth_alert", "eth_hold_3010");
        ReplayScenario liq = scenarios.get("btc_liq");
        assertThat(liq.market()).isEqualTo(MarketType.FUTURES);
        assertThat(liq.priceAt(Duration.ZERO)).isEqualByComparingTo("60000");
        // DS-03a: the 50x demo Long at 60,000 is liquidated at 59,036.2, which the path crosses.
        assertThat(liq.priceAt(liq.length())).isLessThan(new BigDecimal("59036.2"));
        // DS-03b: the dip stops short of it, and the path ends above the entry.
        ReplayScenario near = scenarios.get("btc_near_liq");
        assertThat(near.points().stream()
                        .map(ReplayScenario.Point::price)
                        .min(BigDecimal::compareTo)
                        .orElseThrow())
                .isGreaterThan(new BigDecimal("59036.2"));
        assertThat(near.priceAt(near.length())).isEqualByComparingTo("60200");
        // DS-06a: the alert "ETH price < 2,950" is crossed.
        ReplayScenario eth = scenarios.get("eth_alert");
        assertThat(eth.market()).isEqualTo(MarketType.SPOT);
        assertThat(eth.priceAt(eth.length())).isLessThan(new BigDecimal("2950"));
        assertThat(service.scenarios()).hasSize(5).first().satisfies(first -> {
            assertThat(first.name()).isEqualTo("btc_hold_60000");
            assertThat(first.lengthSeconds()).isZero();
            assertThat(first.firstPrice()).isEqualByComparingTo(first.lastPrice());
        });
    }

    @Test
    void RF07_aReplay_startsOnItsPair_andIsAudited() {
        when(player.start(any())).thenAnswer(call -> {
            ReplayScenario scenario = call.getArgument(0);
            return Optional.of(new ReplayStatus(
                    scenario.name(),
                    scenario.market(),
                    scenario.symbol(),
                    AT,
                    AT.plus(scenario.length()),
                    scenario.priceAt(Duration.ZERO)));
        });

        ReplayResponse started = service.startReplay(ADMIN, new ReplayStartRequest(" btc_liq "));

        assertThat(started.scenario()).isEqualTo("btc_liq");
        assertThat(started.symbol()).isEqualTo("BTCUSDT");
        assertThat(started.endsAt()).isEqualTo(AT.plusSeconds(60));
        AuditEntry entry = audited();
        assertThat(entry.actorId()).isEqualTo(ADMIN);
        assertThat(entry.newValue())
                .containsEntry("key", "market.replay.FUTURES.BTCUSDT")
                .containsEntry("scenario", "btc_liq");
    }

    @Test
    void RF07_aScenarioNotFound_orAPairNotStreamed_isRefused_andNothingIsAudited() {
        when(player.start(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startReplay(ADMIN, new ReplayStartRequest("nope")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.startReplay(ADMIN, new ReplayStartRequest("eth_alert")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("does not stream ETHUSDT");
        verify(admin, never()).audit(any());
    }

    @Test
    void RF07_aReplay_stops_andIsAudited_orIsAConflictWhenNoneRuns() {
        when(player.stop(MarketType.FUTURES, "BTCUSDT")).thenReturn(true);
        when(player.running())
                .thenReturn(List.of(new ReplayStatus(
                        "btc_liq", MarketType.FUTURES, "BTCUSDT", AT, AT.plusSeconds(60), BigDecimal.TEN)));

        assertThat(service.replays()).singleElement().satisfies(replay -> assertThat(replay.price())
                .isEqualByComparingTo("10"));
        service.stopReplay(ADMIN, new ReplayStopRequest(MarketType.FUTURES, " BTCUSDT "));

        assertThat(audited().newValue())
                .containsEntry("key", "market.replay.FUTURES.BTCUSDT")
                .containsEntry("scenario", null);
        assertThatThrownBy(() -> service.stopReplay(ADMIN, new ReplayStopRequest(MarketType.SPOT, "ETHUSDT")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no replay runs on SPOT ETHUSDT");
    }

    @Test
    void RF12_aCut_reachesTheSupervisor_andIsAudited() {
        when(supervisor.simulateLoss(MarketType.SPOT, Duration.ofSeconds(45))).thenReturn(2);

        StreamFaultResponse cut = service.cutStreams(ADMIN, new StreamFaultRequest(MarketType.SPOT, 45));

        assertThat(cut.connectionsCut()).isEqualTo(2);
        assertThat(cut.cutAt()).isEqualTo(AT);
        assertThat(cut.reconnectFrom()).isEqualTo(AT.plusSeconds(45));
        assertThat(audited().newValue())
                .containsEntry("key", "market.fault.streamCut.SPOT")
                .containsEntry("outageSeconds", 45)
                .containsEntry("connectionsCut", 2);
    }

    private AuditEntry audited() {
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(admin).audit(entry.capture());
        return entry.getValue();
    }
}
