package com.cryptopilot.market;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Pairs and coins written straight into the migrated schema, for the market tests that need a pair in a
 * given state — enabled or not on each market (BR-07), trading or not on the exchange — without going through
 * an administrator's use case that does not exist yet.
 */
public final class MarketTestData {

    private final JdbcClient sql;
    private final OffsetDateTime at;

    public MarketTestData(JdbcClient sql, Instant at) {
        this.sql = sql;
        this.at = at.atOffset(ZoneOffset.UTC);
    }

    /**
     * A pair enabled as given; {@code ACTIVE} when it is enabled on either market.
     *
     * @param spotStatus the exchange status on Spot, or null when not listed there
     * @param futuresStatus the exchange status on futures, or null when not listed there
     */
    public UUID pair(
            String symbol,
            boolean spotEnabled,
            boolean futuresEnabled,
            String spotStatus,
            String futuresStatus,
            int displayOrder) {
        UUID id = UUID.randomUUID();
        sql.sql("""
                        insert into crypto_pair (pair_id, base_coin_id, quote_coin_id, symbol, is_spot_enabled,
                                                 is_futures_enabled, pair_status, display_order,
                                                 spot_exchange_status, futures_exchange_status, created_at,
                                                 updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(
                        id,
                        coin(symbol.replace("USDT", "")),
                        coin("USDT"),
                        symbol,
                        spotEnabled,
                        futuresEnabled,
                        spotEnabled || futuresEnabled ? "ACTIVE" : "INACTIVE",
                        displayOrder,
                        spotStatus,
                        futuresStatus,
                        at,
                        at)
                .update();
        return id;
    }

    /** Sets a pair's switches after the fact, as an administrator would. */
    public void enable(UUID pairId, boolean spot, boolean futures) {
        sql.sql("""
                        update crypto_pair set is_spot_enabled = ?, is_futures_enabled = ?,
                               pair_status = case when ? or ? then 'ACTIVE' else 'INACTIVE' end
                         where pair_id = ?""").params(spot, futures, spot, futures, pairId).update();
    }

    /** Empties the market tables these tests write, children first. */
    public void clear() {
        for (String table : new String[] {
            "technical_indicator", "ohlcv", "spot_market_data", "futures_market_data", "crypto_pair", "coin"
        }) {
            sql.sql("delete from " + table).update();
        }
    }

    private UUID coin(String symbol) {
        return sql.sql("select coin_id from coin where symbol = ?")
                .param(symbol)
                .query(UUID.class)
                .optional()
                .orElseGet(() -> {
                    UUID id = UUID.randomUUID();
                    sql.sql("insert into coin (coin_id, symbol, coin_name, created_at, updated_at)"
                                    + " values (?, ?, ?, ?, ?)")
                            .params(id, symbol, symbol, at, at)
                            .update();
                    return id;
                });
    }
}
