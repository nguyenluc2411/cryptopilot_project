package com.cryptopilot.market.service;

import com.cryptopilot.market.dto.response.ChartCandlesResponse;
import java.time.Instant;

/**
 * The candles of the trading chart at any of its sixteen timeframes (TR-35): the stored ones from the database, the
 * others from the exchange through a short cache, and the candle still forming, from the exchange, when it is asked for.
 *
 * <p>Rule: TR-35; BR-08.
 */
public interface ChartCandleService {

    /**
     * @param market {@code spot} or {@code futures}, in any case
     * @param timeframe as the exchange spells it, e.g. {@code 1s} (Spot only), {@code 3m}, {@code 1w}, {@code 1M}
     * @param limit 1 to 1000; 500 when absent
     * @param to the instant the candles must open before; absent for the latest
     * @param includeForming whether the candle still forming is added last; with {@code to} it never is
     */
    ChartCandlesResponse candles(
            String market, String symbol, String timeframe, Integer limit, Instant to, boolean includeForming);
}
