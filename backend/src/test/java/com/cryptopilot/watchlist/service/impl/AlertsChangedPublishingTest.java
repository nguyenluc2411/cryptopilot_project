package com.cryptopilot.watchlist.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.watchlist.AlertTestData;
import com.cryptopilot.watchlist.AlertsChangedRecorder;
import com.cryptopilot.watchlist.dto.request.CreateAlertRequest;
import com.cryptopilot.watchlist.dto.request.UpdateAlertRequest;
import com.cryptopilot.watchlist.model.AlertsChanged;
import com.cryptopilot.watchlist.model.enums.AlertType;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import com.cryptopilot.watchlist.model.enums.TriggerMode;
import com.cryptopilot.watchlist.service.AlertService;
import com.cryptopilot.watchlist.service.WatchlistService;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Every committed change of an alert's stored rule or status announces the alert with {@link AlertsChanged}, which is
 * how the alert engine learns to read it again: edit, pause, resume, delete, and removing the watched pair with its
 * alerts. The services run against the database; the recorder listens after the commit like the engine.
 *
 * <p>Rule: NSF-06, UC-12, UC-14, BR-16; TECHNICAL_DESIGN 7.9.
 */
@SpringBootTest
@Import({TestcontainersConfig.class, AlertsChangedRecorder.class})
class AlertsChangedPublishingTest {

    @Autowired
    private AlertService alerts;

    @Autowired
    private WatchlistService watchlist;

    @Autowired
    private AlertsChangedRecorder recorder;

    @Autowired
    private JdbcClient sql;

    private AlertTestData data;
    private UUID trader;
    private UUID pair;
    private UUID row;

    @BeforeEach
    void watchAPair() {
        data = new AlertTestData(sql);
        trader = data.trader();
        pair = data.pair("AC" + System.nanoTime() % 1_000_000_000L + "USDT");
        row = data.watch(trader, pair);
    }

    @AfterEach
    void removeRows() {
        data.clear();
        recorder.clear();
    }

    @Test
    void NSF06_anEdit_publishesAlertsChanged() {
        UUID alert = create();

        alerts.update(trader, alert, edit("120"));

        assertThat(recorder.received()).containsExactly(AlertsChanged.of(alert));
    }

    @Test
    void NSF06_aPause_publishesAlertsChanged() {
        UUID alert = create();

        alerts.pause(trader, alert);

        assertThat(recorder.received()).containsExactly(AlertsChanged.of(alert));
    }

    @Test
    void NSF06_aResume_publishesAlertsChanged() {
        UUID alert = create();
        alerts.pause(trader, alert);
        recorder.clear();

        alerts.resume(trader, alert);

        assertThat(recorder.received()).containsExactly(AlertsChanged.of(alert));
    }

    @Test
    void NSF06_aDelete_publishesAlertsChanged() {
        UUID alert = create();

        alerts.delete(trader, alert);

        assertThat(recorder.received()).containsExactly(AlertsChanged.of(alert));
    }

    @Test
    void BR16_removingTheWatchedPair_publishesAlertsChangedForEachOfItsAlerts() {
        UUID first = create();
        UUID second = create();

        watchlist.remove(trader, row, true, 2L);

        assertThat(recorder.received()).containsExactly(new AlertsChanged(Set.of(first, second)));
    }

    /** Creates an ACTIVE PRICE alert on the watched pair and forgets the event of its creation. */
    private UUID create() {
        UUID id = alerts.create(
                        trader,
                        new CreateAlertRequest(
                                pair,
                                MarketType.SPOT,
                                AlertType.PRICE,
                                null,
                                null,
                                ConditionOperator.GREATER_THAN,
                                new BigDecimal("100"),
                                TriggerMode.ONCE,
                                null,
                                false,
                                false,
                                null))
                .id();
        recorder.clear();
        return id;
    }

    private static UpdateAlertRequest edit(String threshold) {
        return new UpdateAlertRequest(
                MarketType.SPOT,
                AlertType.PRICE,
                null,
                null,
                ConditionOperator.GREATER_THAN,
                new BigDecimal(threshold),
                TriggerMode.ONCE,
                null,
                false,
                false,
                null);
    }
}
