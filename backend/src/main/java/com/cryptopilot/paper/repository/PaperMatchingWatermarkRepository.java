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
 * The {@code paper_matching_watermark} table: per market and pair, the open time of the last 1-minute candle the paper
 * matching engine closed and fully matched. Kept apart from the plan engine's {@code matching_watermark} (Q-T1).
 *
 * <p>Plain SQL through {@link JdbcClient}, as for {@code matching_watermark}: the table is keyed by market and pair,
 * carries no version and is written by one upsert. The market is passed as the value the check constraint names.
 *
 * <p>Rule: TR-02; NSF-07; Q-T6; A-04.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaperMatchingWatermarkRepository {

    private final JdbcClient sql;

    /** The open time of the pair's last fully matched candle, or empty when none was recorded. */
    public Optional<Instant> lastCandleOpenTime(String marketType, UUID pairId) {
        return sql.sql("select last_candle_open_time from paper_matching_watermark"
                        + " where market_type = ? and pair_id = ?")
                .params(marketType, pairId)
                .query(Instant.class)
                .optional();
    }

    /** Moves the pair's watermark to {@code openTime}, unless it already stands at or after it. */
    public void advance(String marketType, UUID pairId, Instant openTime, Instant now) {
        sql.sql("""
                        insert into paper_matching_watermark (market_type, pair_id, last_candle_open_time, updated_at)
                        values (:marketType, :pairId, :openTime, :now)
                        on conflict (market_type, pair_id) do update
                           set last_candle_open_time = excluded.last_candle_open_time,
                               updated_at            = excluded.updated_at
                         where paper_matching_watermark.last_candle_open_time < excluded.last_candle_open_time""")
                .param("marketType", marketType)
                .param("pairId", pairId)
                .param("openTime", openTime.atOffset(ZoneOffset.UTC))
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
    }
}
