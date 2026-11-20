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
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D-158, P0: a market's state in memory follows {@code market_incident}. A change is seen by its own transaction,
 * lands only once it commits, and leaves nothing behind when it rolls back; a write to an incident that is not open
 * fails instead of changing nothing.
 *
 * <p>Rule: NSF-03; D-158.
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
    void D158_aFailedAudit_leavesTheMarketLive_withNoIncidentAndNoEvent() {
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
    void D158_aCommittedChange_isSeenByItsTransaction_andLandsOnCommit() {
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
    void D158_aRolledBackPause_leavesTheMarketAsItWas() {
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
     * {@link IllegalStateException}, which the repository's translation wraps as a data access exception.
     */
    @Test
    void D158_aWriteToAnUnknownOrClosedIncident_throws() {
        UUID unknown = UuidV7.next();
        assertThatThrownBy(() -> incidents.recovering(unknown, START))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("recovering of " + unknown);
        assertThatThrownBy(() -> incidents.relapse(unknown)).hasMessageContaining("relapse of " + unknown);
        assertThatThrownBy(() -> incidents.toMaintenance(unknown, null, null, null))
                .hasMessageContaining("toMaintenance of " + unknown);
        assertThatThrownBy(() -> incidents.resume(unknown, START)).hasMessageContaining("resume of " + unknown);

        MarketIncident closed = new MarketIncident(
                UuidV7.next(),
                MarketType.SPOT,
                IncidentKind.OUTAGE,
                IncidentReason.STREAM_LOST,
                START.minusSeconds(30),
                START,
                null,
                null,
                null,
                null);
        incidents.open(closed);
        incidents.resume(closed.id(), START.plusSeconds(60));
        assertThatThrownBy(() -> incidents.resume(closed.id(), START.plusSeconds(90)))
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resume of " + closed.id());
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
