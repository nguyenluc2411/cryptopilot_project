package com.cryptopilot.market.model;

import com.cryptopilot.market.MarketType;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Everything the component scores of one closed candle of one series are computed from.
 *
 * <p>Rule: BR-13; TECHNICAL_DESIGN 7.4.
 *
 * @param market the market of the series
 * @param timeframe the timeframe of the series, as BR-08 spells it
 * @param close the candle's close
 * @param volume the candle's base-asset volume
 * @param indicators the indicators after this candle
 * @param previousMacdHistogram the MACD histogram after the previous candle, or {@code null} when it had none
 * @param support the nearest support below the close, or {@code null} when there is none
 * @param resistance the nearest resistance above the close, or {@code null} when there is none
 * @param derivatives the Futures market data; ignored on Spot
 */
public record ComponentInputs(
        MarketType market,
        String timeframe,
        BigDecimal close,
        BigDecimal volume,
        IndicatorSnapshot indicators,
        Double previousMacdHistogram,
        BigDecimal support,
        BigDecimal resistance,
        DerivativesInputs derivatives) {

    public ComponentInputs {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(timeframe, "timeframe");
        Objects.requireNonNull(close, "close");
        Objects.requireNonNull(volume, "volume");
        Objects.requireNonNull(indicators, "indicators");
    }
}
