package com.cryptopilot.market.event;

import com.cryptopilot.market.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The exchange has closed a candle of a stored timeframe and NSF-03 has written it to {@code ohlcv}. Published
 * after the write has committed, on the ordered consumer of the pair, so that listeners — the indicators of
 * NSF-05 — see the candles of one series in order and can read the one they are told about.
 *
 * <p>Published for every close the stream reports, also when the backfill had already stored the row: the
 * close is the fact, the insert only its record. Listeners must be idempotent (TECHNICAL_DESIGN 10).
 *
 * <p>Rule: NSF-03, NSF-05; BR-08 (only closed candles; 15m, 1h, 4h, 1d).
 *
 * @param pairId the pair
 * @param symbol the pair's symbol, e.g. {@code BTCUSDT}
 * @param market the market
 * @param timeframe the timeframe as BR-08 spells it: {@code 15m}, {@code 1h}, {@code 4h} or {@code 1d}
 * @param openTime when the candle opened (UTC)
 * @param closeTime the candle's last millisecond
 * @param open the first price
 * @param high the highest price
 * @param low the lowest price
 * @param close the last price
 * @param baseVolume the base-asset volume
 * @param quoteVolume the quote-asset volume
 */
public record CandleClosed(
        UUID pairId,
        String symbol,
        MarketType market,
        String timeframe,
        Instant openTime,
        Instant closeTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal baseVolume,
        BigDecimal quoteVolume) {}
