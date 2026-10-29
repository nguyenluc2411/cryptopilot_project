package com.cryptopilot.paper.repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code order_matching_watermark} table: per market and pair, the open time of the last 1-minute candle the paper
 * matching engine closed and fully matched. Kept apart from the plan engine's {@code matching_watermark} (Q-T1). A
 * Futures pair also keeps the open time of the last 1-minute mark price candle its mark triggers and liquidations ran
 * over (TR-42); either may be missing while the other is set.
 *
 * <p>Plain SQL through {@link JdbcClient}, as for {@code matching_watermark}: the table is keyed by market and pair,
 * carries no version and is written by one upsert. The market is passed as the value the check constraint names.
 *
 * <p>Rule: TR-02, TR-42; NSF-07; Q-T6; A-04.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaperMatchingWatermarkRepository {

    private final JdbcClient sql;

    /** The open time of the pair's last fully matched candle, or empty when none was recorded. */
    public Optional<Instant> lastCandleOpenTime(String marketType, UUID pairId) {
        return sql.sql("select last_candle_open_time from order_matching_watermark"
                        + " where market_type = ? and pair_id = ? and last_candle_open_time is not null")
                .params(marketType, pairId)
                .query(Instant.class)
                .optional();
    }

    /** The open time of the Futures pair's last mark price candle run over, or empty when none was recorded. */
    public Optional<Instant> lastMarkCandleOpenTime(UUID pairId) {
        return sql.sql("select last_mark_candle_open_time from order_matching_watermark"
                        + " where market_type = 'FUTURES' and pair_id = ? and last_mark_candle_open_time is not null")
                .params(pairId)
                .query(Instant.class)
                .optional();
    }

    /** Moves the Futures pair's mark watermark to {@code openTime}, unless it already stands at or after it. */
    public void advanceMark(UUID pairId, Instant openTime, Instant now) {
        sql.sql("""
                        insert into order_matching_watermark (market_type, pair_id, last_mark_candle_open_time, updated_at)
                        values ('FUTURES', :pairId, :openTime, :now)
                        on conflict (market_type, pair_id) do update
                           set last_mark_candle_open_time = excluded.last_mark_candle_open_time,
                               updated_at                 = excluded.updated_at
                         where order_matching_watermark.last_mark_candle_open_time is null
                            or order_matching_watermark.last_mark_candle_open_time
                               < excluded.last_mark_candle_open_time""")
                .param("pairId", pairId)
                .param("openTime", openTime.atOffset(ZoneOffset.UTC))
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
    }

    /** Moves the pair's watermark to {@code openTime}, unless it already stands at or after it. */
    public void advance(String marketType, UUID pairId, Instant openTime, Instant now) {
        sql.sql("""
                        insert into order_matching_watermark (market_type, pair_id, last_candle_open_time, updated_at)
                        values (:marketType, :pairId, :openTime, :now)
                        on conflict (market_type, pair_id) do update
                           set last_candle_open_time = excluded.last_candle_open_time,
                               updated_at            = excluded.updated_at
                         where order_matching_watermark.last_candle_open_time is null
                            or order_matching_watermark.last_candle_open_time < excluded.last_candle_open_time""")
                .param("marketType", marketType)
                .param("pairId", pairId)
                .param("openTime", openTime.atOffset(ZoneOffset.UTC))
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
    }
}
