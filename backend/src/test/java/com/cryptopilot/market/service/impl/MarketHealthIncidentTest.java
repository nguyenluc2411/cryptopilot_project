package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.util.UuidV7;
import com.cryptopilot.market.MarketRecoveryCheck;
import com.cryptopilot.market.RecoveryProgress;
import com.cryptopilot.market.TradingPausedException;
import com.cryptopilot.market.config.MarketHealthProperties;
import com.cryptopilot.market.event.MarketHealthChanged;
import com.cryptopilot.market.event.MarketPauseAnnounced;
import com.cryptopilot.market.event.MarketRecoveryOverdue;
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
 * K2 and K4 (D-159): the incidents of the markets in {@code market_incident}: at most one open per market, a relapse
 * and maintenance during an outage stay the same incident, and a market with an open incident starts closed after a
 * restart (E1), never live; the monitor's ticks walk an incident from the pause to the reopening, announcing each
 * step once (E3).
 *
 * <p>Each test works on a clean table and leaves it clean, so that the shared application's markets stay live.
 *
 * <p>Rule: NSF-03; D-159.
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
    void D159_theMonitor_asksThePaperEngineAndThePlanFeed() {
        assertThat(recoveryChecks)
                .extracting(check -> ClassUtils.getUserClass(check).getSimpleName())
                .contains("PaperMatchingWorker", "MinuteKlineFeed");
    }

    @Test
    void D159_aMarket_hasAtMostOneOpenIncident() {
        incidents.open(outage(MarketType.FUTURES));
        incidents.open(outage(MarketType.SPOT));

        assertThatThrownBy(() -> incidents.open(outage(MarketType.FUTURES)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(incidents.findAllOpen()).hasSize(2);
    }

    @Test
    void D159_anIncident_recoversRelapsesAndResumes_asOneRow() {
        MarketIncident opened = outage(MarketType.FUTURES);
        incidents.open(opened);

        incidents.recovering(opened.id(), 0, START.plusSeconds(60));
        assertThat(incidents.findOpen(MarketType.FUTURES).orElseThrow().recoveryStartedAt())
                .isEqualTo(START.plusSeconds(60));
        incidents.relapse(opened.id(), 1);
        assertThat(incidents.findOpen(MarketType.FUTURES).orElseThrow().recoveryStartedAt())
                .isNull();
        incidents.recovering(opened.id(), 2, START.plusSeconds(120));
        incidents.resume(opened.id(), 3, START.plusSeconds(180));

        assertThat(incidents.findOpen(MarketType.FUTURES)).isEmpty();
        MarketIncident closed = incidents.findRecent(MarketType.FUTURES, 10).getFirst();
        assertThat(closed.id()).isEqualTo(opened.id());
        assertThat(closed.recoveryStartedAt()).isEqualTo(START.plusSeconds(120));
        assertThat(closed.resumedAt()).isEqualTo(START.plusSeconds(180));
        assertThat(closed.lastLiveAt()).isEqualTo(START.minusSeconds(30));
        assertThat(closed.version()).as("one bump per update").isEqualTo(4);
        assertThat(incidents.findRecent(MarketType.FUTURES, 10)).hasSize(1);
    }

    @Test
    void D159_maintenanceDuringAnOutage_isTheSameIncident() {
        MarketIncident opened = outage(MarketType.SPOT);
        incidents.open(opened);
        UUID admin = UUID.randomUUID();

        incidents.toMaintenance(opened.id(), 0, "Bảo trì", START.plusSeconds(3600), admin);

        MarketIncident open = incidents.findOpen(MarketType.SPOT).orElseThrow();
        assertThat(open.id()).isEqualTo(opened.id());
        assertThat(open.kind()).isEqualTo(IncidentKind.MAINTENANCE);
        assertThat(open.reason()).isEqualTo(IncidentReason.MAINTENANCE);
        assertThat(open.message()).isEqualTo("Bảo trì");
        assertThat(open.createdBy()).isEqualTo(admin);
        assertThat(open.expectedEndAt()).isEqualTo(START.plusSeconds(3600));
        assertThat(open.lastLiveAt()).as("still where the replay starts").isEqualTo(START.minusSeconds(30));
    }

    /** E1: after a restart, an open outage starts paused and open maintenance starts under maintenance. */
    @Test
    void D159_afterARestart_aMarketWithAnOpenIncident_startsClosed() {
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

    /** Maintenance is announced when it begins: a restart soon after does not announce it a second time. */
    @Test
    void D159_aRestartDuringMaintenance_doesNotAnnounceItAgain() {
        MutableTestClock clock = new MutableTestClock(START);
        List<Object> published = new ArrayList<>();
        new MarketHealthServiceImpl(properties(true), incidents, publishedOutside(published), clock)
                .startMaintenance(MarketType.SPOT, "deploy", null);
        assertThat(published).filteredOn(MarketPauseAnnounced.class::isInstance).hasSize(1);

        published.clear();
        clock.advance(Duration.ofSeconds(30));
        MarketHealthServiceImpl restarted =
                new MarketHealthServiceImpl(properties(true), incidents, publishedOutside(published), clock);
        clock.advance(Duration.ofMinutes(3));
        restarted.evaluate(MarketType.SPOT, flowingFor(clock, 20), IncidentReason.STREAM_LOST);

        assertThat(restarted.state(MarketType.SPOT)).isEqualTo(MarketHealthState.MAINTENANCE);
        assertThat(published).noneMatch(MarketPauseAnnounced.class::isInstance);
    }

    /** Maintenance records an incident; ending it with the monitor off resumes it at once, with the monitor on recovers. */
    @Test
    void D159_maintenance_opensAnIncident_andEndingItResumesOrRecovers() {
        MarketHealthServiceImpl off = service(false);
        off.startMaintenance(MarketType.SPOT, "deploy", null);
        assertThat(incidents.findOpen(MarketType.SPOT)).isPresent();
        off.endMaintenance(MarketType.SPOT);
        assertThat(off.state(MarketType.SPOT)).isEqualTo(MarketHealthState.LIVE);
        assertThat(incidents.findOpen(MarketType.SPOT)).isEmpty();
        assertThat(incidents.findRecent(MarketType.SPOT, 1).getFirst().resumedAt())
                .isNotNull();

        MarketHealthServiceImpl on = watched(service(true));
        on.startMaintenance(MarketType.FUTURES, null, null);
        on.endMaintenance(MarketType.FUTURES);
        assertThat(on.state(MarketType.FUTURES)).isEqualTo(MarketHealthState.RECOVERING);
        assertThat(incidents.findOpen(MarketType.FUTURES).orElseThrow().recoveryStartedAt())
                .isNotNull();
    }

    /** Enabled but with no monitor running (streams off), nothing would end a recovery: ending maintenance reopens. */
    @Test
    void D159_endingMaintenance_withoutARunningMonitor_reopensAtOnce() {
        MarketHealthServiceImpl unwatched = service(true);
        unwatched.startMaintenance(MarketType.SPOT, null, null);
        unwatched.endMaintenance(MarketType.SPOT);

        assertThat(unwatched.state(MarketType.SPOT)).isEqualTo(MarketHealthState.LIVE);
        assertThat(incidents.findOpen(MarketType.SPOT)).isEmpty();
    }

    /**
     * E3, one incident end to end: paused after 30 s of silence, recovering after 15 s of data, paused again on a
     * break without a second announcement, recovering again, live once recovered; the row and the events tell it.
     */
    @Test
    void D159_theTicks_walkOneIncident_fromThePauseToTheReopening() {
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
    void D159_aSilentStream_withTheRestApiBanned_pausesAtOnce() {
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
    void D159_maintenance_holdsTheMarket_andEndingItWhileTheDataIsLost_pauses() {
        MutableTestClock clock = new MutableTestClock(START);
        List<Object> published = new ArrayList<>();
        MarketHealthServiceImpl health =
                watched(new MarketHealthServiceImpl(properties(true), incidents, publishedOutside(published), clock));

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
                .as("maintenance is announced at once")
                .containsExactly("MarketTradingPaused", "MarketPauseAnnounced", "MarketRecoveryStarted");
    }

    /**
     * D-159 data-lost matrix: the market's exchange data counts as lost, so the screens show the fallback, only while
     * it is paused, or recovering from an incident in which the data really was lost; never live or under
     * maintenance, and never while recovering from a maintenance during which the data kept flowing.
     */
    @Test
    void D159_dataLost_isTrueOnlyWhileTheExchangeDataIsMissingOrRefilled() {
        MutableTestClock clock = new MutableTestClock(START);
        MarketHealthServiceImpl health =
                watched(new MarketHealthServiceImpl(properties(true), incidents, event -> {}, clock));
        MarketType m = MarketType.FUTURES;
        assertThat(health.dataLost(m)).as("M1 live").isFalse();

        // M4, M5: maintenance over a live market, ended while the data flows.
        health.startMaintenance(m, null, null);
        assertThat(health.dataLost(m)).as("M4 maintenance").isFalse();
        health.endMaintenance(m);
        assertThat(health.state(m)).isEqualTo(MarketHealthState.RECOVERING);
        assertThat(health.dataLost(m)).as("M5 recovering from maintenance").isFalse();
        health.evaluate(m, flowingFor(clock, 20).withRecoveryDone(true), IncidentReason.STREAM_LOST);
        assertThat(health.state(m)).isEqualTo(MarketHealthState.LIVE);
        assertThat(health.dataLost(m)).as("M9 live again").isFalse();

        // M2, M3: an outage.
        health.evaluate(m, silentSince(clock.instant().minusSeconds(30)), IncidentReason.STREAM_LOST);
        assertThat(health.dataLost(m)).as("M2 paused").isTrue();
        clock.advance(Duration.ofSeconds(20));
        health.evaluate(m, flowingFor(clock, 20), IncidentReason.STREAM_LOST);
        assertThat(health.state(m)).isEqualTo(MarketHealthState.RECOVERING);
        assertThat(health.dataLost(m)).as("M3 recovering from an outage").isTrue();

        // M7: maintenance taking over the outage, then ended.
        health.startMaintenance(m, null, null);
        assertThat(health.dataLost(m))
                .as("M7 maintenance shows the exchange's prices")
                .isFalse();
        assertThat(service(true).dataLost(m))
                .as("M8 restart under that maintenance")
                .isFalse();
        health.endMaintenance(m);
        assertThat(health.dataLost(m)).as("M7 recovering after it").isTrue();
        // The restarted service takes over: the old one's status is behind the row.
        health = watched(new MarketHealthServiceImpl(properties(true), incidents, event -> {}, clock));
        health.endMaintenance(m);
        assertThat(health.dataLost(m)).as("M8 restarted, then ended").isTrue();
        health.evaluate(m, flowingFor(clock, 20).withRecoveryDone(true), IncidentReason.STREAM_LOST);
        assertThat(health.dataLost(m)).as("M9 reopened").isFalse();

        // M9, M6: a new maintenance starts clean; ended while the data is lost, it pauses and recovers as lost.
        health.startMaintenance(m, null, null);
        health.endMaintenance(m);
        assertThat(health.dataLost(m)).as("M9 the flag is not carried over").isFalse();
        health.evaluate(m, silentSince(clock.instant().minusSeconds(60)), IncidentReason.STREAM_LOST);
        assertThat(health.state(m)).isEqualTo(MarketHealthState.PAUSED);
        assertThat(health.dataLost(m)).as("M6 paused").isTrue();
        clock.advance(Duration.ofSeconds(20));
        health.evaluate(m, flowingFor(clock, 20), IncidentReason.STREAM_LOST);
        assertThat(health.state(m)).isEqualTo(MarketHealthState.RECOVERING);
        assertThat(health.dataLost(m)).as("M6 recovering after the relapse").isTrue();
        health.evaluate(m, flowingFor(clock, 20).withRecoveryDone(true), IncidentReason.STREAM_LOST);

        // M8: restarts with an open incident of each kind.
        incidents.open(outage(MarketType.SPOT));
        assertThat(service(true).dataLost(MarketType.SPOT)).as("M8 open outage").isTrue();
        sql.sql("delete from market_incident").update();
        MarketHealthServiceImpl fresh = service(true);
        fresh.startMaintenance(MarketType.SPOT, null, null);
        MarketHealthServiceImpl again = watched(service(true));
        again.endMaintenance(MarketType.SPOT);
        assertThat(again.dataLost(MarketType.SPOT))
                .as("M8 open maintenance over a live market")
                .isFalse();
    }

    /** Records what the service publishes to other modules; the module's own state changes are left out. */
    private static ApplicationEventPublisher publishedOutside(List<Object> published) {
        return event -> {
            if (!(event instanceof MarketHealthChanged)) {
                published.add(event);
            }
        };
    }

    /**
     * K9: an outage is announced to the Traders once it has lasted notify-after (2 minutes), once however many ticks
     * follow, and its reopening says so; one that ends sooner is never announced.
     */
    @Test
    void D159_anOutage_isAnnouncedOnceAfterNotifyAfter_andAShortOneNever() {
        MutableTestClock clock = new MutableTestClock(START);
        List<Object> published = new ArrayList<>();
        MarketHealthServiceImpl health =
                new MarketHealthServiceImpl(properties(true), incidents, publishedOutside(published), clock);

        health.evaluate(MarketType.SPOT, silentSince(START.minusSeconds(30)), IncidentReason.STREAM_LOST);
        clock.advance(Duration.ofSeconds(60));
        health.evaluate(MarketType.SPOT, silentSince(START.minusSeconds(30)), IncidentReason.STREAM_LOST);
        assertThat(published).noneMatch(event -> event instanceof MarketPauseAnnounced);
        clock.advance(Duration.ofSeconds(60));
        health.evaluate(MarketType.SPOT, silentSince(START.minusSeconds(30)), IncidentReason.STREAM_LOST);
        clock.advance(Duration.ofSeconds(1));
        health.evaluate(MarketType.SPOT, flowingFor(clock, 20), IncidentReason.STREAM_LOST);
        health.evaluate(MarketType.SPOT, flowingFor(clock, 20).withRecoveryDone(true), IncidentReason.STREAM_LOST);

        assertThat(published)
                .extracting(event -> event.getClass().getSimpleName())
                .containsExactly(
                        "MarketTradingPaused", "MarketPauseAnnounced", "MarketRecoveryStarted", "MarketTradingResumed");
        MarketPauseAnnounced announced = (MarketPauseAnnounced) published.get(1);
        assertThat(announced.lastLiveAt()).isEqualTo(START.minusSeconds(30));
        assertThat(announced.reason()).isEqualTo(IncidentReason.STREAM_LOST);
        assertThat(((MarketTradingResumed) published.getLast()).announced()).isTrue();

        published.clear();
        Instant later = clock.instant();
        health.evaluate(MarketType.SPOT, silentSince(later.minusSeconds(30)), IncidentReason.STREAM_LOST);
        clock.advance(Duration.ofSeconds(20));
        health.evaluate(MarketType.SPOT, flowingFor(clock, 15), IncidentReason.STREAM_LOST);
        health.evaluate(MarketType.SPOT, flowingFor(clock, 15).withRecoveryDone(true), IncidentReason.STREAM_LOST);

        assertThat(published).noneMatch(event -> event instanceof MarketPauseAnnounced);
        assertThat(((MarketTradingResumed) published.getLast()).announced()).isFalse();
    }

    /**
     * D-159: while an incident is open the engines replay from where its data stopped, however long ago, but never more
     * than max-outage-replay (7 days) back; for an hour after it ends too, for the timed work that runs once it is open.
     */
    @Test
    void D159_theCatchUp_reachesBackToTheIncident_atMostSevenDays() {
        MutableTestClock clock = new MutableTestClock(START);
        MarketHealthServiceImpl health = new MarketHealthServiceImpl(properties(true), incidents, event -> {}, clock);
        assertThat(health.catchUpFrom(MarketType.FUTURES)).isEmpty();

        Instant lastLive = START.minus(Duration.ofDays(3));
        health.evaluate(MarketType.FUTURES, silentSince(lastLive), IncidentReason.STREAM_LOST);
        assertThat(health.catchUpFrom(MarketType.FUTURES)).contains(lastLive);
        assertThat(health.catchUpFrom(MarketType.SPOT)).isEmpty();
        assertThat(service(true).catchUpFrom(MarketType.FUTURES))
                .as("after a restart")
                .contains(lastLive);

        health.evaluate(MarketType.FUTURES, flowingFor(clock, 20), IncidentReason.STREAM_LOST);
        health.evaluate(MarketType.FUTURES, flowingFor(clock, 20).withRecoveryDone(true), IncidentReason.STREAM_LOST);
        assertThat(health.state(MarketType.FUTURES)).isEqualTo(MarketHealthState.LIVE);
        clock.advance(Duration.ofMinutes(59));
        assertThat(health.catchUpFrom(MarketType.FUTURES)).as("just ended").contains(lastLive);
        assertThat(new MarketHealthServiceImpl(properties(true), incidents, event -> {}, clock)
                        .catchUpFrom(MarketType.FUTURES))
                .as("just ended, after a restart")
                .contains(lastLive);
        clock.advance(Duration.ofMinutes(2));
        assertThat(health.catchUpFrom(MarketType.FUTURES)).isEmpty();

        health.evaluate(
                MarketType.SPOT, silentSince(clock.instant().minus(Duration.ofDays(9))), IncidentReason.STREAM_LOST);
        assertThat(health.catchUpFrom(MarketType.SPOT)).contains(clock.instant().minus(Duration.ofDays(7)));
    }

    /**
     * D-159: a recovery that has not ended after recovery-timeout (15 minutes) is told to the administrators, once; it
     * still reopens by itself once the engines have caught up.
     */
    @Test
    void D159_aRecoveryLongerThanTheTimeout_isToldOnce_andStillReopens() {
        MutableTestClock clock = new MutableTestClock(START);
        List<Object> published = new ArrayList<>();
        MarketHealthServiceImpl health =
                new MarketHealthServiceImpl(properties(true), incidents, publishedOutside(published), clock);
        health.evaluate(MarketType.SPOT, silentSince(START.minusSeconds(30)), IncidentReason.STREAM_LOST);
        health.evaluate(MarketType.SPOT, flowingFor(clock, 20), IncidentReason.STREAM_LOST);
        assertThat(health.state(MarketType.SPOT)).isEqualTo(MarketHealthState.RECOVERING);

        clock.advance(Duration.ofMinutes(14));
        health.evaluate(MarketType.SPOT, flowingFor(clock, 20), IncidentReason.STREAM_LOST);
        assertThat(published).noneMatch(event -> event instanceof MarketRecoveryOverdue);
        clock.advance(Duration.ofMinutes(2));
        health.evaluate(MarketType.SPOT, flowingFor(clock, 20), IncidentReason.STREAM_LOST);
        clock.advance(Duration.ofMinutes(2));
        health.evaluate(MarketType.SPOT, flowingFor(clock, 20), IncidentReason.STREAM_LOST);

        assertThat(published)
                .filteredOn(event -> event instanceof MarketRecoveryOverdue)
                .singleElement()
                .isInstanceOfSatisfying(MarketRecoveryOverdue.class, overdue -> {
                    assertThat(overdue.market()).isEqualTo(MarketType.SPOT);
                    assertThat(overdue.lastLiveAt()).isEqualTo(START.minusSeconds(30));
                    assertThat(overdue.recoveringSince()).isEqualTo(START);
                    assertThat(overdue.beyondReplay()).isFalse();
                });
        assertThat(health.evaluate(
                        MarketType.SPOT, flowingFor(clock, 20).withRecoveryDone(true), IncidentReason.STREAM_LOST))
                .isEqualTo(MarketHealthState.LIVE);
    }

    /**
     * D-159: data lost longer than max-outage-replay cannot all be replayed, so the market never reopens by itself: it
     * stays recovering, the administrators are told at once, and only one of them reopens it.
     */
    @Test
    void D159_anOutageBeyondTheReplay_staysRecovering_untilAnAdministratorReopensIt() {
        MutableTestClock clock = new MutableTestClock(START);
        List<Object> published = new ArrayList<>();
        MarketHealthServiceImpl health =
                new MarketHealthServiceImpl(properties(true), incidents, publishedOutside(published), clock);
        assertThatThrownBy(() -> health.forceResume(MarketType.FUTURES, null))
                .as("a live market")
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(ErrorCode.DATA_CONFLICT));

        health.evaluate(MarketType.FUTURES, silentSince(START.minus(Duration.ofDays(8))), IncidentReason.STREAM_LOST);
        assertThatThrownBy(() -> health.forceResume(MarketType.FUTURES, null))
                .as("a paused market, its data still lost")
                .isInstanceOf(BusinessException.class);
        health.evaluate(MarketType.FUTURES, flowingFor(clock, 20), IncidentReason.STREAM_LOST);
        assertThat(health.evaluate(
                        MarketType.FUTURES, flowingFor(clock, 20).withRecoveryDone(true), IncidentReason.STREAM_LOST))
                .isEqualTo(MarketHealthState.RECOVERING);
        assertThat(published)
                .filteredOn(event -> event instanceof MarketRecoveryOverdue)
                .singleElement()
                .isInstanceOfSatisfying(MarketRecoveryOverdue.class, overdue -> assertThat(overdue.beyondReplay())
                        .isTrue());

        health.forceResume(MarketType.FUTURES, UUID.randomUUID());

        assertThat(health.state(MarketType.FUTURES)).isEqualTo(MarketHealthState.LIVE);
        assertThat(incidents.findOpen(MarketType.FUTURES)).isEmpty();
        assertThat(published.getLast()).isInstanceOf(MarketTradingResumed.class);
    }

    /** The progress the monitor reads is kept while the market recovers, and only then. */
    @Test
    void D159_theRecoveryProgress_isKeptWhileRecovering_only() {
        MutableTestClock clock = new MutableTestClock(START);
        MarketHealthServiceImpl health = new MarketHealthServiceImpl(properties(true), incidents, event -> {}, clock);
        health.recoveryProgress(MarketType.SPOT, new RecoveryProgress(1, 2));
        assertThat(health.recoveryProgress(MarketType.SPOT)).as("live").isEmpty();

        health.evaluate(MarketType.SPOT, silentSince(START.minusSeconds(30)), IncidentReason.STREAM_LOST);
        health.evaluate(MarketType.SPOT, flowingFor(clock, 20), IncidentReason.STREAM_LOST);
        assertThat(health.recoveryProgress(MarketType.SPOT)).as("not read yet").isEmpty();
        health.recoveryProgress(MarketType.SPOT, new RecoveryProgress(1, 2));
        assertThat(health.recoveryProgress(MarketType.SPOT)).contains(new RecoveryProgress(1, 2));

        health.evaluate(MarketType.SPOT, flowingFor(clock, 20).withRecoveryDone(true), IncidentReason.STREAM_LOST);
        assertThat(health.recoveryProgress(MarketType.SPOT)).isEmpty();
    }

    private static MarketHealthServiceImpl watched(MarketHealthServiceImpl health) {
        health.monitorStarted();
        return health;
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
