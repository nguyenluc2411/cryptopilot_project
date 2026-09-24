package com.cryptopilot.market.event;

import com.cryptopilot.market.MarketType;
import java.time.Instant;
import java.util.UUID;

/**
 * NSF-03 has seen a closed candle that does not follow the latest one stored for its series: the candles
 * opened from {@code from} to {@code to}, both inclusive, are missing. NSF-02 fetches them.
 *
 * <p>Raised when the stream was down (a lost connection, a restart) or a closed candle never reached storage,
 * and for a series the stream reaches before the backfill has filled it — then {@code from} is the start of
 * the series' configured depth, because the backfill resumes after the latest stored candle and would never
 * go back behind the one the stream just wrote.
 *
 * <p>Rule: NSF-02, NSF-03 (any detected gap triggers NSF-02); TECHNICAL_DESIGN 7.1 step 4; A-33.
 *
 * @param pairId the pair
 * @param symbol the pair's symbol, e.g. {@code BTCUSDT}
 * @param market the market
 * @param timeframe the timeframe as BR-08 spells it: {@code 15m}, {@code 1h}, {@code 4h} or {@code 1d}
 * @param from the open time of the first missing candle
 * @param to the open time of the last missing candle
 */
public record GapDetected(UUID pairId, String symbol, MarketType market, String timeframe, Instant from, Instant to) {

    /** The same gap, now starting later: what is left of it after part was filled. */
    public GapDetected startingAt(Instant newFrom) {
        return new GapDetected(pairId, symbol, market, timeframe, newFrom, to);
    }
}
