package com.cryptopilot.support;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Cleans up after a test that closed a market (D-159): its incidents announce themselves to the Traders and the
 * administrators after the commit, so the notices are awaited, then removed with the incidents, before the test's
 * accounts go.
 */
public final class MarketIncidents {

    private MarketIncidents() {}

    /** Waits for the incident notices to be delivered, then removes them and every incident. */
    public static void removeAll(JdbcClient sql) {
        await().atMost(Duration.ofSeconds(10))
                .until(() -> sql.sql("""
                                        select count(*) from event_publication
                                         where (listener_id like '%IncidentNotices%'
                                                or listener_id like '%MarketNotificationListener%')
                                           and completion_date is null""").query(Long.class).single() == 0);
        sql.sql("delete from notification where source_key like 'MARKET\\_%'").update();
        sql.sql("delete from market_incident").update();
    }
}
