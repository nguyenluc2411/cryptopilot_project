package com.cryptopilot.watchlist;

import com.cryptopilot.market.MarketTestData;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Traders, pairs, watchlist rows and alerts written straight to the tables, in any state, for the alert engine's
 * tests; {@link #clear()} removes everything it wrote.
 */
public final class AlertTestData {

    private final JdbcClient sql;
    private final MarketTestData market;
    private final List<UUID> accounts = new ArrayList<>();
    private final List<UUID> pairs = new ArrayList<>();

    public AlertTestData(JdbcClient sql) {
        this.sql = sql;
        this.market = new MarketTestData(sql, Instant.now());
    }

    /** A FREE Trader. */
    public UUID trader() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = now();
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@t055.invalid", now, now).update();
        accounts.add(id);
        return id;
    }

    /** A pair enabled on both markets, with tick filters, and its symbol. */
    public UUID pair(String symbol) {
        UUID id = market.pair(symbol, true, true, "TRADING", "TRADING", 0);
        sql.sql("""
                        update crypto_pair set spot_tick_size = 0.01, spot_step_size = 0.001, spot_min_notional = 5,
                               futures_tick_size = 0.1, futures_step_size = 0.001, futures_min_notional = 5
                         where pair_id = ?""").param(id).update();
        pairs.add(id);
        return id;
    }

    /** A watchlist row of the Trader on the pair. */
    public UUID watch(UUID trader, UUID pair) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = now();
        sql.sql("""
                        insert into watchlist (watchlist_id, user_id, pair_id, sort_order, added_at, created_at,
                                               updated_at)
                        values (?, ?, ?, 0, ?, ?, ?)""").params(id, trader, pair, now, now, now).update();
        return id;
    }

    /**
     * A PRICE alert in the given state.
     *
     * @param cooldownMinutes the cooldown, or {@code null}
     * @param expiresAt the expiry, or {@code null}
     */
    public UUID priceAlert(
            UUID trader,
            UUID watchlistId,
            String market,
            String condition,
            String threshold,
            String triggerMode,
            Integer cooldownMinutes,
            String status,
            Instant expiresAt) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = now();
        sql.sql("""
                        insert into alert (alert_id, user_id, watchlist_id, market_type, alert_type, condition_operator,
                                           threshold_value, trigger_mode, cooldown_minutes, alert_status, expires_at,
                                           created_at, updated_at)
                        values (?, ?, ?, ?, 'PRICE', ?, ?::numeric, ?, ?, ?, ?, ?, ?)""")
                .params(
                        id,
                        trader,
                        watchlistId,
                        market,
                        condition,
                        threshold,
                        triggerMode,
                        cooldownMinutes,
                        status,
                        expiresAt == null ? null : expiresAt.atOffset(ZoneOffset.UTC),
                        now,
                        now)
                .update();
        return id;
    }

    /** An ACTIVE RSI_14 alert, which the engine does not evaluate yet. */
    public UUID indicatorAlert(UUID trader, UUID watchlistId) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = now();
        sql.sql("""
                        insert into alert (alert_id, user_id, watchlist_id, market_type, alert_type, indicator_name,
                                           timeframe, condition_operator, threshold_value, trigger_mode,
                                           alert_status, created_at, updated_at)
                        values (?, ?, ?, 'SPOT', 'INDICATOR', 'RSI_14', '1h', 'LESS_THAN', 30, 'ONCE', 'ACTIVE', ?,
                                ?)""").params(id, trader, watchlistId, now, now).update();
        return id;
    }

    public String status(UUID alertId) {
        return sql.sql("select alert_status from alert where alert_id = ?")
                .param(alertId)
                .query(String.class)
                .single();
    }

    public int triggerCount(UUID alertId) {
        return sql.sql("select trigger_count from alert where alert_id = ?")
                .param(alertId)
                .query(Integer.class)
                .single();
    }

    public Instant lastTriggeredAt(UUID alertId) {
        return sql.sql("select last_triggered_at from alert where alert_id = ?")
                .param(alertId)
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant)
                .orElse(null);
    }

    public void clear() {
        for (UUID account : accounts) {
            sql.sql("delete from alert where user_id = ?").param(account).update();
            sql.sql("delete from watchlist where user_id = ?").param(account).update();
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
        for (UUID pair : pairs) {
            List<UUID> coins = sql.sql("select base_coin_id from crypto_pair where pair_id = ?")
                    .param(pair)
                    .query(UUID.class)
                    .list();
            sql.sql("delete from crypto_pair where pair_id = ?").param(pair).update();
            for (UUID coin : coins) {
                sql.sql("""
                                delete from coin where coin_id = ?
                                   and not exists (select 1 from crypto_pair
                                                    where base_coin_id = coin.coin_id or quote_coin_id = coin.coin_id)""").param(coin).update();
            }
        }
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
