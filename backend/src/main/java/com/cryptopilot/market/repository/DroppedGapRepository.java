package com.cryptopilot.market.repository;

import com.cryptopilot.market.event.GapDetected;
import com.cryptopilot.market.model.DroppedGap;
import com.cryptopilot.market.model.enums.MarketType;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code dropped_backfill_gap} table: one row per gap range the backfill gave up on.
 *
 * <p>Plain SQL through {@link JdbcClient} rather than a JPA entity, as for {@code binance_ban}: the table is keyed
 * by the range rather than by a UUID, carries no version and is written by one upsert (TECHNICAL_DESIGN 5.5). The
 * market is passed as the value the column's check constraint names, {@code SPOT} or {@code FUTURES}.
 *
 * <p>Rule: NSF-02, NSF-03.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class DroppedGapRepository {

    private final JdbcClient sql;

    /**
     * Writes a dropped gap, replacing the failure count, error and instant when the same range was dropped before.
     * Not transactional here; the caller's is.
     */
    public void save(DroppedGap dropped) {
        GapDetected gap = dropped.gap();
        sql.sql("""
                        insert into dropped_backfill_gap
                               (market_type, pair_id, timeframe, gap_from, gap_to, symbol,
                                failure_count, last_error, dropped_at)
                        values (:marketType, :pairId, :timeframe, :from, :to, :symbol,
                                :failureCount, :lastError, :droppedAt)
                        on conflict (market_type, pair_id, timeframe, gap_from, gap_to) do update
                           set symbol        = excluded.symbol,
                               failure_count = excluded.failure_count,
                               last_error    = excluded.last_error,
                               dropped_at    = excluded.dropped_at""")
                .param("marketType", gap.market().name())
                .param("pairId", gap.pairId())
                .param("timeframe", gap.timeframe())
                .param("from", gap.from().atOffset(ZoneOffset.UTC))
                .param("to", gap.to().atOffset(ZoneOffset.UTC))
                .param("symbol", gap.symbol())
                .param("failureCount", dropped.failureCount())
                .param("lastError", dropped.lastError())
                .param("droppedAt", dropped.droppedAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    /** Whether a recorded range of the same pair, market and timeframe contains the whole of this gap. */
    public boolean covers(GapDetected gap) {
        return sql.sql("""
                        select exists (
                               select 1 from dropped_backfill_gap
                                where market_type = :marketType and pair_id = :pairId and timeframe = :timeframe
                                  and gap_from <= :from and gap_to >= :to)""")
                .param("marketType", gap.market().name())
                .param("pairId", gap.pairId())
                .param("timeframe", gap.timeframe())
                .param("from", gap.from().atOffset(ZoneOffset.UTC))
                .param("to", gap.to().atOffset(ZoneOffset.UTC))
                .query(Boolean.class)
                .single();
    }

    /** The dropped gaps of a market, most recently dropped first. */
    public List<DroppedGap> findByMarket(MarketType market) {
        return sql.sql("""
                        select pair_id, symbol, timeframe, gap_from, gap_to, failure_count, last_error, dropped_at
                          from dropped_backfill_gap
                         where market_type = :marketType
                         order by dropped_at desc, gap_from""")
                .param("marketType", market.name())
                .query((row, rowNumber) -> new DroppedGap(
                        new GapDetected(
                                row.getObject("pair_id", UUID.class),
                                row.getString("symbol"),
                                market,
                                row.getString("timeframe"),
                                row.getObject("gap_from", OffsetDateTime.class).toInstant(),
                                row.getObject("gap_to", OffsetDateTime.class).toInstant()),
                        row.getInt("failure_count"),
                        row.getString("last_error"),
                        row.getObject("dropped_at", OffsetDateTime.class).toInstant()))
                .list();
    }
}
