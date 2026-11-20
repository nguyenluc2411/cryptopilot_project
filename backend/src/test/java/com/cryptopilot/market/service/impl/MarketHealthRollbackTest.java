package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.cryptopilot.admin.AdminApi;
import com.cryptopilot.common.util.UuidV7;
import com.cryptopilot.market.client.BinanceRestClient;
import com.cryptopilot.market.config.MarketHealthProperties;
import com.cryptopilot.market.dto.request.MaintenanceRequest;
import com.cryptopilot.market.event.MarketPauseAnnounced;
import com.cryptopilot.market.event.MarketRecoveryOverdue;
import com.cryptopilot.market.model.HealthSignals;
import com.cryptopilot.market.model.MarketIncident;
import com.cryptopilot.market.model.enums.IncidentKind;
import com.cryptopilot.market.model.enums.IncidentReason;
import com.cryptopilot.market.model.enums.MarketHealthState;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.repository.MarketIncidentRepository;
import com.cryptopilot.market.service.DisplayPriceService;
import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.support.TestcontainersConfig;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D-159, P0: a market's state in memory follows {@code market_incident}. A change is seen by its own transaction,
 * lands only once it commits, and leaves nothing behind when it rolls back; a write to an incident that is not open
 * fails instead of changing nothing.
 *
 * <p>Rule: NSF-03; D-159.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class MarketHealthRollbackTest {

    private static final Instant START = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @Autowired
    private MarketIncidentRepository incidents;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private PlatformTransactionManager transactions;

    private final List<Object> heard = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void noIncidents() {
        sql.sql("delete from market_incident").update();
        heard.clear();
    }

    /** The audit fails inside the maintenance call: the incident rolls back, and so does the state. */
    @Test
    void D159_aFailedAudit_leavesTheMarketLive_withNoIncidentAndNoEvent() {
        MarketHealthServiceImpl health = health(Clock.systemUTC());
        AdminApi admin = mock(AdminApi.class);
        doThrow(new IllegalStateException("audit down")).when(admin).audit(any());
        MarketStatusServiceImpl status = new MarketStatusServiceImpl(
                health,
                incidents,
                admin,
                mock(MarketLiveness.class),
                mock(BinanceRestClient.class),
                mock(DisplayPriceService.class),
                Clock.systemUTC());

        assertThatThrownBy(() -> inTransaction()
                        .executeWithoutResult(done -> status.maintenance(
                                UUID.randomUUID(), new MaintenanceRequest(MarketType.FUTURES, true, "deploy"))))
                .hasMessage("audit down");

        assertThat(health.state(MarketType.FUTURES)).isEqualTo(MarketHealthState.LIVE);
        health.requireTrading(MarketType.FUTURES);
        assertThat(incidents.findOpen(MarketType.FUTURES)).isEmpty();
        assertThat(heard).isEmpty();
    }

    /** A committed change lands after the commit; inside its transaction it is seen already, by that one alone. */
    @Test
    void D159_aCommittedChange_isSeenByItsTransaction_andLandsOnCommit() {
        MarketHealthServiceImpl health = health(Clock.systemUTC());

        inTransaction().executeWithoutResult(done -> {
            health.startMaintenance(MarketType.SPOT, null, null);
            assertThat(health.state(MarketType.SPOT)).isEqualTo(MarketHealthState.MAINTENANCE);
            assertThat(heard).as("before the commit").isEmpty();
        });

        assertThat(health.state(MarketType.SPOT)).isEqualTo(MarketHealthState.MAINTENANCE);
        assertThat(incidents.findOpen(MarketType.SPOT)).isPresent();
        assertThat(heard).isNotEmpty();
    }

    /** A pause the monitor's tick rolls back leaves the market as it was: live, or reconnecting. */
    @Test
    void D159_aRolledBackPause_leavesTheMarketAsItWas() {
        MutableTestClock clock = new MutableTestClock(START);
        MarketHealthServiceImpl health = health(clock);
        Instant lastLive = START.minusSeconds(30);

        rolledBack(
                () -> assertThat(health.evaluate(MarketType.FUTURES, silentSince(lastLive), IncidentReason.STREAM_LOST))
                        .isEqualTo(MarketHealthState.PAUSED));
        assertThat(health.state(MarketType.FUTURES)).isEqualTo(MarketHealthState.LIVE);

        assertThat(health.evaluate(MarketType.SPOT, silentSince(START.minusSeconds(12)), IncidentReason.STREAM_LOST))
                .isEqualTo(MarketHealthState.RECONNECTING);
        heard.clear();
        clock.advance(Duration.ofSeconds(20));
        rolledBack(() -> assertThat(health.evaluate(
                        MarketType.SPOT, silentSince(START.minusSeconds(12)), IncidentReason.STREAM_LOST))
                .isEqualTo(MarketHealthState.PAUSED));
        assertThat(health.state(MarketType.SPOT)).isEqualTo(MarketHealthState.RECONNECTING);

        assertThat(incidents.findAllOpen()).isEmpty();
        assertThat(heard).isEmpty();
    }

    /**
     * A write to an incident that is not there, or already closed, changes no row and is refused: an
     * {@link OptimisticLockingFailureException}, which the API answers as a conflict.
     */
    @Test
    void D159_aWriteToAnUnknownOrClosedIncident_throws() {
        UUID unknown = UuidV7.next();
        assertThatThrownBy(() -> incidents.recovering(unknown, 0, START))
                .isInstanceOf(OptimisticLockingFailureException.class)
                .hasMessageContaining("recovering of " + unknown);
        assertThatThrownBy(() -> incidents.relapse(unknown, 0)).hasMessageContaining("relapse of " + unknown);
        assertThatThrownBy(() -> incidents.toMaintenance(unknown, 0, null, null, null))
                .hasMessageContaining("toMaintenance of " + unknown);
        assertThatThrownBy(() -> incidents.resume(unknown, 0, START)).hasMessageContaining("resume of " + unknown);

        MarketIncident closed = outage(MarketType.SPOT);
        incidents.open(closed);
        incidents.resume(closed.id(), 0, START.plusSeconds(60));
        assertThatThrownBy(() -> incidents.resume(closed.id(), 1, START.plusSeconds(90)))
                .isInstanceOf(OptimisticLockingFailureException.class)
                .hasMessageContaining("resume of " + closed.id());
    }

    /** An update that names a version the row has moved past changes nothing and is refused. */
    @Test
    void D159_anUpdateFromAStaleVersion_throws() {
        MarketIncident open = outage(MarketType.SPOT);
        incidents.open(open);
        incidents.recovering(open.id(), 0, START.plusSeconds(10));

        assertThatThrownBy(() -> incidents.toMaintenance(open.id(), 0, "deploy", null, null))
                .isInstanceOf(OptimisticLockingFailureException.class)
                .hasMessageContaining("toMaintenance of " + open.id());
        MarketIncident row = incidents.findOpen(MarketType.SPOT).orElseThrow();
        assertThat(row.kind()).isEqualTo(IncidentKind.OUTAGE);
        assertThat(row.version()).isEqualTo(1);
    }

    /**
     * The monitor's recovery and an administrator's maintenance start from the same paused state: the second waits on
     * the first's row, then finds its version gone, throws and rolls back; memory and row both say recovering.
     */
    @Test
    void D159_twoTransactionsFromOnePausedState_onlyTheFirstLands() throws Exception {
        MutableTestClock clock = new MutableTestClock(START);
        incidents.open(outage(MarketType.FUTURES));
        MarketHealthServiceImpl health = health(clock);
        CountDownLatch recovered = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> monitor = threads.submit(() -> inTransaction().executeWithoutResult(done -> {
                assertThat(health.evaluate(MarketType.FUTURES, flowing(clock), IncidentReason.STREAM_LOST))
                        .isEqualTo(MarketHealthState.RECOVERING);
                recovered.countDown();
                awaitQuietly(commit);
            }));
            assertThat(recovered.await(30, TimeUnit.SECONDS)).isTrue();
            Future<?> admin = threads.submit(() -> inTransaction()
                    .executeWithoutResult(done -> health.startMaintenance(MarketType.FUTURES, "deploy", null)));
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .until(() -> sql.sql("select count(*) from pg_locks where not granted")
                                    .query(Long.class)
                                    .single()
                            > 0);
            commit.countDown();

            monitor.get(30, TimeUnit.SECONDS);
            assertThatThrownBy(() -> admin.get(30, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(OptimisticLockingFailureException.class);
        } finally {
            commit.countDown();
            threads.shutdownNow();
        }

        MarketIncident row = incidents.findOpen(MarketType.FUTURES).orElseThrow();
        assertThat(row.kind()).isEqualTo(IncidentKind.OUTAGE);
        assertThat(row.recoveryStartedAt()).isNotNull();
        assertThat(row.version()).isEqualTo(1);
        assertThat(health.state(MarketType.FUTURES)).isEqualTo(MarketHealthState.RECOVERING);
        assertThat(heard).noneMatch(MarketPauseAnnounced.class::isInstance);
    }

    /**
     * A committed change whose start state moved meanwhile is not applied over the newer one: the market is reloaded
     * from the database, here an incident another writer turned back into an outage.
     */
    @Test
    void D159_aCommittedChange_whoseStateMoved_reloadsTheMarketFromTheDatabase() {
        MarketHealthServiceImpl health = health(new MutableTestClock(START));
        TransactionTemplate apart = inTransaction();
        apart.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        inTransaction().executeWithoutResult(done -> {
            assertThat(health.evaluate(
                            MarketType.SPOT, silentSince(START.minusSeconds(12)), IncidentReason.STREAM_LOST))
                    .isEqualTo(MarketHealthState.RECONNECTING);
            apart.executeWithoutResult(other -> health.startMaintenance(MarketType.SPOT, "deploy", null));
            assertThat(health.state(MarketType.SPOT)).isEqualTo(MarketHealthState.RECONNECTING);
            sql.sql(
                            "update market_incident set incident_kind = 'OUTAGE', reason = 'STREAM_LOST', version = version + 1")
                    .update();
        });

        assertThat(health.state(MarketType.SPOT)).isEqualTo(MarketHealthState.PAUSED);
        assertThat(health.dataLost(MarketType.SPOT)).isTrue();
    }

    /**
     * A reload at run time keeps a recovery the database holds: the incident's recovery began, so the market stays
     * recovering since the row's time, and the overdue notice counts from it.
     */
    @Test
    void D159_aReloadOfARecoveringIncident_keepsItRecovering_sinceTheRowsTime() {
        MutableTestClock clock = new MutableTestClock(START);
        MarketHealthServiceImpl health = health(clock);
        TransactionTemplate apart = inTransaction();
        apart.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Instant recoveringAt = START.plusSeconds(5);

        inTransaction().executeWithoutResult(done -> {
            assertThat(health.evaluate(
                            MarketType.SPOT, silentSince(START.minusSeconds(12)), IncidentReason.STREAM_LOST))
                    .isEqualTo(MarketHealthState.RECONNECTING);
            apart.executeWithoutResult(other -> health.startMaintenance(MarketType.SPOT, "deploy", null));
            sql.sql("""
                            update market_incident
                               set incident_kind = 'OUTAGE', reason = 'STREAM_LOST', recovery_started_at = :at,
                                   version = version + 1""").param("at", Timestamp.from(recoveringAt)).update();
        });

        assertThat(health.state(MarketType.SPOT)).isEqualTo(MarketHealthState.RECOVERING);
        assertThat(health.dataLost(MarketType.SPOT)).isTrue();

        heard.clear();
        clock.advance(Duration.ofMinutes(15).plusSeconds(6));
        assertThat(health.evaluate(MarketType.SPOT, flowing(clock), IncidentReason.STREAM_LOST))
                .isEqualTo(MarketHealthState.RECOVERING);
        assertThat(heard)
                .filteredOn(MarketRecoveryOverdue.class::isInstance)
                .singleElement()
                .extracting(event -> ((MarketRecoveryOverdue) event).recoveringSince())
                .isEqualTo(recoveringAt);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static HealthSignals flowing(MutableTestClock clock) {
        return new HealthSignals(clock.instant(), clock.instant().minusSeconds(20), true, false, false);
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

    private MarketHealthServiceImpl health(Clock clock) {
        return new MarketHealthServiceImpl(
                new MarketHealthProperties(
                        true, Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(15)),
                incidents,
                heardAfterCommit(),
                clock);
    }

    /** What the listeners of other modules would hear: the events of a transaction once it committed. */
    private ApplicationEventPublisher heardAfterCommit() {
        return event -> {
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                heard.add(event);
                return;
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    heard.add(event);
                }
            });
        };
    }

    private TransactionTemplate inTransaction() {
        return new TransactionTemplate(transactions);
    }

    private void rolledBack(Runnable work) {
        inTransaction().executeWithoutResult(done -> {
            work.run();
            done.setRollbackOnly();
        });
    }

    private static HealthSignals silentSince(Instant lastLive) {
        return new HealthSignals(lastLive, lastLive, true, false, false);
    }
}
