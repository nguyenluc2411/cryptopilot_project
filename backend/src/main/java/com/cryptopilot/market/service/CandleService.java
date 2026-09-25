package com.cryptopilot.market.service;

import com.cryptopilot.market.dto.response.CandlesResponse;
import java.time.Instant;

/**
 * The stored closed candles of a pair, for the charts of SCR-01 and SCR-10 (UC-09); implemented by
 * {@link com.cryptopilot.market.service.impl.CandleServiceImpl}.
 *
 * <p>Rule: UC-09, BR-07, BR-08; SRS 3.3.1, 3.3.2; D-48.
 */
public interface CandleService {

    /**
     * The latest closed candles opened in {@code [from, to)}, at most {@code limit}, oldest first.
     *
     * @param market {@code spot} or {@code futures}, in any case
     * @param symbol the symbol, in any case
     * @param timeframe {@code 15m}, {@code 1h}, {@code 4h} or {@code 1d} (BR-08)
     * @param limit how many candles at most; {@code null} for the configured default
     * @param from the earliest open time; {@code null} for no lower bound
     * @param to the open time the candles must be before; {@code null} for now
     * @throws com.cryptopilot.common.exception.BusinessException {@code VALIDATION_FAILED} (MSG01) for an unknown
     *     market or timeframe, a limit out of range, a range that ends in the future, is empty or backwards, or holds
     *     more candles than one request may return
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException when no pair with that symbol is enabled on
     *     that market (BR-07)
     */
    CandlesResponse closedCandles(
            String market, String symbol, String timeframe, Integer limit, Instant from, Instant to);
}
