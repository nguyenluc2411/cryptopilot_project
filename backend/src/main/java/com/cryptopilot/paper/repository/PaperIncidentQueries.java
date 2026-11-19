package com.cryptopilot.paper.repository;

import com.cryptopilot.market.model.enums.MarketType;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * What the notices of a market incident read of the paper tables (D-158): who has something waiting on the market's
 * prices, and what a catch-up did to whom. Plain SQL through {@link JdbcClient}: read-only aggregates across tables
 * that no entity maps as one.
 *
 * <p>Rule: NSF-03; D-158.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaperIncidentQueries {

    private final JdbcClient sql;

    /** The Traders with a working order on the market, or, on Futures, an open position. */
    public List<UUID> tradersOn(MarketType market) {
        return sql.sql("""
                        select user_id from trade_order
                         where market_type = :market and order_status in ('NEW', 'PENDING_NEW', 'PARTIALLY_FILLED')
                        union
                        select user_id from futures_position
                         where position_status = 'OPEN' and :market = 'FUTURES'""").param("market", market.name()).query(UUID.class).list();
    }

    /** How many fills a replay applied to each Trader's orders on the market, applied from {@code from} to {@code to}. */
    public Map<UUID, Long> replayedFills(MarketType market, Instant from, Instant to) {
        return counts(sql.sql("""
                        select user_id, count(*) as applied from order_fill
                         where market_type = :market and fill_source = 'REPLAY'
                           and traded_at >= :from and traded_at <= :to
                         group by user_id""")
                .param("market", market.name())
                .param("from", from.atOffset(ZoneOffset.UTC))
                .param("to", to.atOffset(ZoneOffset.UTC)));
    }

    /** How many Futures positions of each Trader were liquidated from {@code from} to {@code to}. */
    public Map<UUID, Long> liquidations(Instant from, Instant to) {
        return counts(
                sql.sql("""
                        select user_id, count(*) as applied from futures_position
                         where close_reason = 'LIQUIDATION' and closed_at >= :from and closed_at <= :to
                         group by user_id""").param("from", from.atOffset(ZoneOffset.UTC)).param("to", to.atOffset(ZoneOffset.UTC)));
    }

    private static Map<UUID, Long> counts(JdbcClient.StatementSpec statement) {
        Map<UUID, Long> counts = new LinkedHashMap<>();
        statement
                .query((row, number) -> {
                    counts.put(row.getObject("user_id", UUID.class), row.getLong("applied"));
                    return null;
                })
                .list();
        return counts;
    }
}
