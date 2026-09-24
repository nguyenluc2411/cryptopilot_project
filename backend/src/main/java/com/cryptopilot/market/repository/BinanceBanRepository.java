package com.cryptopilot.market.repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code binance_ban} table: one row per market, holding when its latest IP ban ends.
 *
 * <p>Plain SQL through {@link JdbcClient} rather than a JPA entity, because the table is not one: it is
 * keyed by the market rather than by a UUID, carries no version and is written by one statement, an
 * upsert on the key (TECHNICAL_DESIGN 5.5 keeps entities for single-UUID tables). The market is passed as
 * the value the column's check constraint names, {@code SPOT} or {@code FUTURES}.
 *
 * <p>Rule: BR-09; TECHNICAL_DESIGN 6 and 7.1.2.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class BinanceBanRepository {

    private final JdbcClient sql;

    /** When the recorded ban of this market ends, or empty when none was ever recorded. */
    public Optional<Instant> bannedUntil(String marketType) {
        return sql.sql("select banned_until from binance_ban where market_type = ?")
                .param(marketType)
                .query(Instant.class)
                .optional();
    }

    /** Writes the ban of this market, replacing the previous one. Not transactional here; the caller's is. */
    public void save(String marketType, Instant bannedUntil, String reason, Instant now) {
        sql.sql("""
                        insert into binance_ban (market_type, banned_until, reason, updated_at)
                        values (:marketType, :bannedUntil, :reason, :now)
                        on conflict (market_type) do update
                           set banned_until = excluded.banned_until,
                               reason       = excluded.reason,
                               updated_at   = excluded.updated_at""")
                .param("marketType", marketType)
                .param("bannedUntil", bannedUntil.atOffset(ZoneOffset.UTC))
                .param("reason", reason)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
    }
}
