package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.common.util.UuidV7;
import com.cryptopilot.market.TradingPausedException;
import com.cryptopilot.market.config.MarketHealthProperties;
import com.cryptopilot.market.model.MarketIncident;
import com.cryptopilot.market.model.enums.IncidentKind;
import com.cryptopilot.market.model.enums.IncidentReason;
import com.cryptopilot.market.model.enums.MarketHealthState;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.repository.MarketIncidentRepository;
import com.cryptopilot.support.TestcontainersConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * K2 (D-158): the incidents of the markets in {@code market_incident}: at most one open per market, a relapse and
 * maintenance during an outage stay the same incident, and a market with an open incident starts closed after a
 * restart (E1), never live.
 *
 * <p>Each test works on a clean table and leaves it clean, so that the shared application's markets stay live.
 *
 * <p>Rule: NSF-03; D-158.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class MarketHealthIncidentTest {

    private static final Instant START = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @Autowired
    private MarketIncidentRepository incidents;

    @Autowired
    private JdbcClient sql;

    @BeforeEach
    @AfterEach
    void noIncidents() {
        sql.sql("delete from market_incident").update();
    }

    @Test
    void D158_aMarket_hasAtMostOneOpenIncident() {
        incidents.open(outage(MarketType.FUTURES));
        incidents.open(outage(MarketType.SPOT));

        assertThatThrownBy(() -> incidents.open(outage(MarketType.FUTURES)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(incidents.findAllOpen()).hasSize(2);
    }

    @Test
    void D158_anIncident_recoversRelapsesAndResumes_asOneRow() {
        MarketIncident opened = outage(MarketType.FUTURES);
        incidents.open(opened);

        incidents.recovering(opened.id(), START.plusSeconds(60));
        assertThat(incidents.findOpen(MarketType.FUTURES).orElseThrow().recoveryStartedAt())
                .isEqualTo(START.plusSeconds(60));
        incidents.relapse(opened.id());
        assertThat(incidents.findOpen(MarketType.FUTURES).orElseThrow().recoveryStartedAt())
                .isNull();
        incidents.recovering(opened.id(), START.plusSeconds(120));
        incidents.resume(opened.id(), START.plusSeconds(180));

        assertThat(incidents.findOpen(MarketType.FUTURES)).isEmpty();
        MarketIncident closed = incidents.findRecent(MarketType.FUTURES, 10).getFirst();
        assertThat(closed.id()).isEqualTo(opened.id());
        assertThat(closed.recoveryStartedAt()).isEqualTo(START.plusSeconds(120));
        assertThat(closed.resumedAt()).isEqualTo(START.plusSeconds(180));
        assertThat(closed.lastLiveAt()).isEqualTo(START.minusSeconds(30));
        assertThat(incidents.findRecent(MarketType.FUTURES, 10)).hasSize(1);
    }

    @Test
    void D158_maintenanceDuringAnOutage_isTheSameIncident() {
        MarketIncident opened = outage(MarketType.SPOT);
        incidents.open(opened);
        UUID admin = UUID.randomUUID();

        incidents.toMaintenance(opened.id(), "Bảo trì", admin);

        MarketIncident open = incidents.findOpen(MarketType.SPOT).orElseThrow();
        assertThat(open.id()).isEqualTo(opened.id());
        assertThat(open.kind()).isEqualTo(IncidentKind.MAINTENANCE);
        assertThat(open.reason()).isEqualTo(IncidentReason.MAINTENANCE);
        assertThat(open.message()).isEqualTo("Bảo trì");
        assertThat(open.createdBy()).isEqualTo(admin);
        assertThat(open.lastLiveAt()).as("still where the replay starts").isEqualTo(START.minusSeconds(30));
    }

    /** E1: after a restart, an open outage starts paused and open maintenance starts under maintenance. */
    @Test
    void D158_afterARestart_aMarketWithAnOpenIncident_startsClosed() {
        incidents.open(outage(MarketType.FUTURES));

        MarketHealthServiceImpl restarted = service(true);

        assertThat(restarted.state(MarketType.FUTURES)).isEqualTo(MarketHealthState.PAUSED);
        assertThat(restarted.state(MarketType.SPOT)).isEqualTo(MarketHealthState.LIVE);
        assertThatThrownBy(() -> restarted.requireTrading(MarketType.FUTURES))
                .isInstanceOfSatisfying(TradingPausedException.class, refused -> {
                    assertThat(refused.since()).isEqualTo(START.minusSeconds(30));
                    assertThat(refused.state()).isEqualTo(MarketHealthState.PAUSED);
                });

        restarted.startMaintenance(MarketType.SPOT, "deploy", null);
        assertThat(service(true).state(MarketType.SPOT)).isEqualTo(MarketHealthState.MAINTENANCE);
    }

    /** Maintenance records an incident; ending it with the monitor off resumes it at once, with the monitor on recovers. */
    @Test
    void D158_maintenance_opensAnIncident_andEndingItResumesOrRecovers() {
        MarketHealthServiceImpl off = service(false);
        off.startMaintenance(MarketType.SPOT, "deploy", null);
        assertThat(incidents.findOpen(MarketType.SPOT)).isPresent();
        off.endMaintenance(MarketType.SPOT);
        assertThat(off.state(MarketType.SPOT)).isEqualTo(MarketHealthState.LIVE);
        assertThat(incidents.findOpen(MarketType.SPOT)).isEmpty();
        assertThat(incidents.findRecent(MarketType.SPOT, 1).getFirst().resumedAt())
                .isNotNull();

        MarketHealthServiceImpl on = service(true);
        on.startMaintenance(MarketType.FUTURES, null, null);
        on.endMaintenance(MarketType.FUTURES);
        assertThat(on.state(MarketType.FUTURES)).isEqualTo(MarketHealthState.RECOVERING);
        assertThat(incidents.findOpen(MarketType.FUTURES).orElseThrow().recoveryStartedAt())
                .isNotNull();
    }

    private MarketHealthServiceImpl service(boolean monitor) {
        return new MarketHealthServiceImpl(
                new MarketHealthProperties(
                        monitor, Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(15)),
                incidents,
                Clock.systemUTC());
    }

    private static MarketIncident outage(MarketType market) {
        return new MarketIncident(
                UuidV7.next(),
                market,
                IncidentKind.OUTAGE,
                IncidentReason.STREAM_LOST,
                START.minusSeconds(30),
                START,
                null,
                null,
                null,
                null);
    }
}
