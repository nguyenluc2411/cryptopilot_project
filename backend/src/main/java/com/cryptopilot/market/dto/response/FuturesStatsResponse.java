package com.cryptopilot.market.dto.response;

import java.time.Instant;

/**
 * The statistics of a futures pair, one block per source, each with its own instant.
 *
 * <p>The blocks come from different writers at different rhythms — the mark price every minute (NSF-03), open
 * interest and the long/short ratio every five minutes (NSF-04), settlements at each funding time — so each carries
 * the instant of the value it holds and a client can tell how old each one is. A block with nothing stored yet is
 * absent; no value is carried from another instant or made up (D-45).
 *
 * <p>Rule: UC-09; NSF-03, NSF-04; BR-10, BR-11; SRS 3.3.1, 3.3.3; D-45, D-46.
 *
 * @param symbol the symbol
 * @param serverTime the server's instant when it answered
 * @param price the latest stored mark price and predicted funding, or absent
 * @param openInterest the latest open interest reading, or absent
 * @param longShortRatio the latest long/short account ratio reading, or absent
 * @param lastFundingSettlement the latest settled funding rate, or absent
 */
public record FuturesStatsResponse(
        String symbol,
        Instant serverTime,
        FuturesPriceResponse price,
        OpenInterestResponse openInterest,
        LongShortRatioResponse longShortRatio,
        FundingSettlementResponse lastFundingSettlement) {}
