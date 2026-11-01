package com.cryptopilot.support;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Removes the notifications the plans of a test's pair caused: MSG22 of every EXECUTED plan, MSG23 or MSG24 of every closed
 * position and MSG54 of every invalidated plan. Each is written after its change commits, on another thread, so it
 * first waits until every one of them is there; a test that deletes the pair's rows, or the owner, before then would
 * leave a row behind or fail on the owner's key.
 *
 * <p>Rule: BR-34, BR-35, BR-68; MSG22, MSG23, MSG24, MSG54; Q-81.
 */
public final class PlanNotifications {

    private PlanNotifications() {}

    /** Waits for the notifications of the pair's fills, closes and invalidations, then deletes them. */
    public static void removeOfPair(JdbcClient sql, UUID pair) {
        await("the notifications of the plans of " + pair)
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(20))
                .until(() -> missing(sql, pair) == 0);
        sql.sql("""
                        delete from notification where source_key in
                               (select 'PLAN_FILLED:' || plan_id from trading_plan where pair_id = ?
                                union all
                                select 'POSITION_CLOSED:' || journal_id from trading_journal where pair_id = ?
                                union all
                                select 'PLAN_INVALIDATED:' || plan_id from trading_plan where pair_id = ?)""").params(pair, pair, pair).update();
    }

    private static long missing(JdbcClient sql, UUID pair) {
        return sql.sql("""
                        select (select count(*) from trading_plan p
                                 where p.pair_id = ? and p.plan_status = 'EXECUTED'
                                   and not exists (select 1 from notification n
                                                    where n.source_key = 'PLAN_FILLED:' || p.plan_id))
                             + (select count(*) from trading_journal j
                                 where j.pair_id = ? and j.trade_status = 'CLOSED'
                                   and not exists (select 1 from notification n
                                                    where n.source_key = 'POSITION_CLOSED:' || j.journal_id))
                             + (select count(*) from trading_plan p
                                 where p.pair_id = ? and p.plan_status = 'INVALIDATED'
                                   and not exists (select 1 from notification n
                                                    where n.source_key = 'PLAN_INVALIDATED:' || p.plan_id))""").params(pair, pair, pair).query(Long.class).single();
    }
}
