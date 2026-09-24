package com.cryptopilot.market.repository;

import com.cryptopilot.market.client.StreamMessage.MarkPriceMessage;
import com.cryptopilot.market.client.StreamMessage.TickerMessage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The periodic snapshot hypertables {@code spot_market_data} and {@code futures_market_data}.
 *
 * <p>Plain SQL, as for {@code ohlcv}: immutable time-series rows with a composite key. The key is
 * {@code (pair_id, snapshot_time)} and every insert is {@code ON CONFLICT DO NOTHING}, so a snapshot written
 * twice for the same instant — a repeated run — is stored once.
 *
 * <p>The futures row carries what the mark price stream reports: mark price, index price, the predicted
 * funding rate and the next funding time. Open interest and the long/short ratios come from NSF-04 (T-023)
 * and stay empty in a row NSF-03 writes. Settled funding rates are not here at all but in
 * {@code funding_rate_history}.
 *
 * <p>Rule: NSF-03 (periodic snapshots every minute); NSF-17 (their retention); TECHNICAL_DESIGN 6, 7.1 step 7.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class MarketSnapshotRepository {

    private final JdbcClient sql;

    /** Writes one Spot snapshot per pair at this instant; answers how many were new. */
    public int insertSpot(Instant at, Map<UUID, TickerMessage> tickers) {
        int inserted = 0;
        for (Map.Entry<UUID, TickerMessage> entry : tickers.entrySet()) {
            TickerMessage ticker = entry.getValue();
            inserted += sql.sql("""
                            insert into spot_market_data (pair_id, snapshot_time, last_price, best_bid_price,
                                                          best_ask_price, high_price_24h, low_price_24h,
                                                          price_change_percent_24h, base_volume_24h, quote_volume_24h)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            on conflict (pair_id, snapshot_time) do nothing""")
                    .params(
                            entry.getKey(),
                            Timestamp.from(at),
                            ticker.lastPrice(),
                            ticker.bestBidPrice(),
                            ticker.bestAskPrice(),
                            ticker.highPrice24h(),
                            ticker.lowPrice24h(),
                            ticker.priceChangePercent24h(),
                            ticker.baseVolume24h(),
                            ticker.quoteVolume24h())
                    .update();
        }
        return inserted;
    }

    /** Writes one futures snapshot per pair at this instant; answers how many were new. */
    public int insertFutures(Instant at, Map<UUID, MarkPriceMessage> markPrices) {
        int inserted = 0;
        for (Map.Entry<UUID, MarkPriceMessage> entry : markPrices.entrySet()) {
            MarkPriceMessage mark = entry.getValue();
            inserted += sql.sql("""
                            insert into futures_market_data (pair_id, snapshot_time, mark_price, index_price,
                                                             funding_rate, next_funding_time)
                            values (?, ?, ?, ?, ?, ?)
                            on conflict (pair_id, snapshot_time) do nothing""")
                    .params(
                            entry.getKey(),
                            Timestamp.from(at),
                            mark.markPrice(),
                            mark.indexPrice(),
                            mark.fundingRate(),
                            Timestamp.from(mark.nextFundingTime()))
                    .update();
        }
        return inserted;
    }
}
