package com.cryptopilot.market.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The candles of the trading chart at any of its timeframes (TR-35), oldest first, the one still forming last when it
 * was asked for.
 *
 * @param symbol the symbol
 * @param market {@code SPOT} or {@code FUTURES}
 * @param timeframe the timeframe as the exchange spells it, e.g. {@code 3m} or {@code 1M}
 * @param source {@code STORED} for a timeframe the database keeps (15m, 1h, 4h, 1d; BR-08), {@code EXCHANGE} for one
 *     read from the exchange; the forming candle always comes from the exchange
 * @param candles the candles, oldest first
 */
public record ChartCandlesResponse(
        String symbol, String market, String timeframe, String source, List<Candle> candles) {

    /**
     * One candle.
     *
     * @param closed whether it is closed; only the last one may be still forming
     */
    public record Candle(
            Instant openTime,
            Instant closeTime,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            BigDecimal baseVolume,
            BigDecimal quoteVolume,
            Long tradeCount,
            boolean closed) {}
}
