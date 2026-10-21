package com.cryptopilot.support;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Collection;
import java.util.UUID;
import org.awaitility.core.ConditionTimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Removes the notifications that community events wrote for a test's accounts. The events are delivered after the
 * request commits, on another thread, so a test that deletes its accounts first waits for the deliveries to finish:
 * a notification written after the delete would hold the account's row.
 */
public final class InAppNotifications {

    private InAppNotifications() {}

    /** Waits for the pending notification deliveries, then deletes the notifications of these accounts. */
    public static void remove(JdbcClient sql, Collection<UUID> accounts) {
        try {
            await().atMost(Duration.ofSeconds(10))
                    .pollInterval(Duration.ofMillis(50))
                    .until(() -> sql.sql("""
                                    select count(*) from event_publication
                                     where listener_id like '%NotificationListener%' and completion_date is null
                                       and coalesce(status, '') <> 'FAILED'""").query(Long.class).single() == 0);
        } catch (ConditionTimeoutException stillRunning) {
            // Delete what is there; a delivery still running writes nothing for an account deleted meanwhile.
        }
        for (UUID account : accounts) {
            sql.sql("delete from notification where user_id = ?").param(account).update();
        }
    }
}
