package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.common.util.UuidV7;
import com.cryptopilot.market.MarketRecoveryCheck;
import com.cryptopilot.market.TradingPausedException;
import com.cryptopilot.market.config.MarketHealthProperties;
import com.cryptopilot.market.event.MarketHealthChanged;
import com.cryptopilot.market.event.MarketRecoveryStarted;
import com.cryptopilot.market.event.MarketTradingPaused;
import com.cryptopilot.market.event.MarketTradingResumed;
import com.cryptopilot.market.model.HealthSignals;
import com.cryptopilot.market.model.MarketIncident;
import com.cryptopilot.market.model.enums.IncidentKind;
import com.cryptopilot.market.model.enums.IncidentReason;
import com.cryptopilot.market.model.enums.MarketHealthState;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.repository.MarketIncidentRepository;
import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.support.TestcontainersConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.ClassUtils;

/**
 * K2 and K4 (D-158): the incidents of the markets in {@code market_incident}: at most one open per market, a relapse
 * and maintenance during an outage stay the same incident, and a market with an open incident starts closed after a
 * restart (E1), never live; the monitor's ticks walk an incident from the pause to the reopening, announcing each
 * step once (E3).
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

    @Autowired
    private List<MarketRecoveryCheck> recoveryChecks;

    @BeforeEach
    @AfterEach
    void noIncidents() {
        sql.sql("delete from market_incident").update();
    }

    /** I6: a recovering market waits on both engines that run on its prices, the paper orders' and the plans'. */
    @Test
    void D158_theMonitor_asksThePaperEngineAndThePlanFeed() {
        assertThat(recoveryChecks)
                .extracting(check -> ClassUtils.getUserClass(check).getSimpleName())
                .contains("PaperMatchingWorker", "MinuteKlineFeed");
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

    /**
     * E3, one incident end to end: paused after 30 s of silence, recovering after 15 s of data, paused again on a
     * break without a second announcement, recovering again, live once recovered; the row and the events tell it.
     */
    @Test
    void D158_theTicks_walkOneIncident_fromThePauseToTheReopening() {
        MutableTestClock clock = new MutableTestClock(START);
        List<Object> published = new ArrayList<>();
        MarketHealthServiceImpl health =
                new MarketHealthServiceImpl(properties(true), incidents, publishedOutside(published), clock);
        Instant lastLive = START.minusSeconds(30);

        assertThat(health.evaluate(MarketType.FUTURES, silentSince(lastLive), IncidentReason.STREAM_LOST))
                .isEqualTo(MarketHealthState.PAUSED);
        MarketIncident open = incidents.findOpen(MarketType.FUTURES).orElseThrow();
        assertThat(open.lastLiveAt()).isEqualTo(lastLive);
        assertThat(open.reason()).isEqualTo(IncidentReason.STREAM_LOST);
        assertThat(health.state(MarketType.SPOT)).isEqualTo(MarketHealthState.LIVE);

        clock.advance(Duration.ofSeconds(60));
        assertThat(health.evaluate(MarketType.FUTURES, flowingFor(clock, 10), IncidentReason.STREAM_LOST))
                .as("not stable yet")
                .isEqualTo(MarketHealthState.PAUSED);
        assertThat(health.evaluate(MarketType.FUTURES, flowingFor(clock, 15), IncidentReason.STREAM_LOST))
                .isEqualTo(MarketHealthState.RECOVERING);
        clock.advance(Duration.ofSeconds(20));
        assertThat(health.evaluate(
                        MarketType.FUTURES, silentSince(clock.instant().minusSeconds(12)), IncidentReason.STREAM_LOST))
                .as("a relapse")
                .isEqualTo(MarketHealthState.PAUSED);
        assertThat(incidents.findOpen(MarketType.FUTURES).orElseThrow().recoveryStartedAt())
                .isNull();
        clock.advance(Duration.ofSeconds(30));
        health.evaluate(MarketType.FUTURES, flowingFor(clock, 20), IncidentReason.STREAM_LOST);
        assertThat(health.evaluate(MarketType.FUTURES, flowingFor(clock, 20), IncidentReason.STREAM_LOST))
                .as("still replaying")
                .isEqualTo(MarketHealthState.RECOVERING);
        assertThatThrownBy(() -> health.requireTrading(MarketType.FUTURES)).isInstanceOf(TradingPausedException.class);
        assertThat(health.evaluate(
                        MarketType.FUTURES, flowingFor(clock, 20).withRecoveryDone(true), IncidentReason.STREAM_LOST))
                .isEqualTo(MarketHealthState.LIVE);

        health.requireTrading(MarketType.FUTURES);
        assertThat(incidents.findOpen(MarketType.FUTURES)).isEmpty();
        MarketIncident closed = incidents.findRecent(MarketType.FUTURES, 1).getFirst();
        assertThat(closed.id()).isEqualTo(open.id());
        assertThat(closed.resumedAt()).isEqualTo(clock.instant());
        assertThat(published)
                .extracting(event -> event.getClass().getSimpleName())
                .containsExactly(
                        "MarketTradingPaused",
                        "MarketRecoveryStarted",
                        "MarketRecoveryStarted",
                        "MarketTradingResumed");
        assertThat(((MarketTradingResumed) published.getLast()).lastLiveAt()).isEqualTo(lastLive);
        assertThat(((MarketTradingPaused) published.getFirst()).incidentId()).isEqualTo(open.id());
        assertThat(((MarketRecoveryStarted) published.get(1)).lastLiveAt()).isEqualTo(lastLive);
    }

    /** A stream silent 10 s while the REST API is banned pauses at once, for the ban; a short silence alone does not. */
    @Test
    void D158_aSilentStream_withTheRestApiBanned_pausesAtOnce() {
        MutableTestClock clock = new MutableTestClock(START);
        MarketHealthServiceImpl health = new MarketHealthServiceImpl(properties(true), incidents, event -> {}, clock);
        Instant lastLive = START.minusSeconds(12);

        assertThat(health.evaluate(MarketType.SPOT, silentSince(lastLive), IncidentReason.STREAM_LOST))
                .isEqualTo(MarketHealthState.RECONNECTING);
        health.requireTrading(MarketType.SPOT);
        assertThat(incidents.findOpen(MarketType.SPOT)).isEmpty();

        HealthSignals banned = new HealthSignals(lastLive, lastLive, false, false, false);
        assertThat(health.evaluate(MarketType.SPOT, banned, IncidentReason.IP_BANNED))
                .isEqualTo(MarketHealthState.PAUSED);
        assertThat(incidents.findOpen(MarketType.SPOT).orElseThrow().reason()).isEqualTo(IncidentReason.IP_BANNED);
    }

    /** E13, E14: maintenance holds a market whatever its data; ended while the data is lost, it pauses, not reopens. */
    @Test
    void D158_maintenance_holdsTheMarket_andEndingItWhileTheDataIsLost_pauses() {
        MutableTestClock clock = new MutableTestClock(START);
        List<Object> published = new ArrayList<>();
        MarketHealthServiceImpl health =
                new MarketHealthServiceImpl(properties(true), incidents, publishedOutside(published), clock);

        health.startMaintenance(MarketType.FUTURES, "maintenance", null);
        assertThat(health.evaluate(MarketType.FUTURES, flowingFor(clock, 600), IncidentReason.STREAM_LOST))
                .isEqualTo(MarketHealthState.MAINTENANCE);

        health.endMaintenance(MarketType.FUTURES);
        assertThat(health.state(MarketType.FUTURES)).isEqualTo(MarketHealthState.RECOVERING);
        assertThat(health.evaluate(MarketType.FUTURES, silentSince(START.minusSeconds(60)), IncidentReason.STREAM_LOST))
                .isEqualTo(MarketHealthState.PAUSED);
        assertThat(incidents.findAllOpen()).hasSize(1);
        assertThat(published)
                .extracting(event -> event.getClass().getSimpleName())
                .containsExactly("MarketTradingPaused", "MarketRecoveryStarted");
    }

    /** Records what the service publishes to other modules; the module's own state changes are left out. */
    private static ApplicationEventPublisher publishedOutside(List<Object> published) {
        return event -> {
            if (!(event instanceof MarketHealthChanged)) {
                published.add(event);
            }
        };
    }

    private static HealthSignals silentSince(Instant lastLive) {
        return new HealthSignals(lastLive, lastLive, true, false, false);
    }

    private static HealthSignals flowingFor(MutableTestClock clock, long seconds) {
        return new HealthSignals(clock.instant(), clock.instant().minusSeconds(seconds), true, false, false);
    }

    private static MarketHealthProperties properties(boolean monitor) {
        return new MarketHealthProperties(
                monitor, Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(15));
    }

    private MarketHealthServiceImpl service(boolean monitor) {
        return new MarketHealthServiceImpl(
                new MarketHealthProperties(
                        monitor, Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(15)),
                incidents,
                event -> {},
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
