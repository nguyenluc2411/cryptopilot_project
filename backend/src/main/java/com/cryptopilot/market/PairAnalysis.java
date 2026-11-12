package com.cryptopilot.market;

import com.cryptopilot.market.model.enums.CapReason;
import com.cryptopilot.market.model.enums.DominantSide;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.model.enums.ScoredSide;
import com.cryptopilot.market.model.enums.SetupStyle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * The analysis of a pair as SCR-10 / SCR-11 show it to one Trader, for a module that explains it (the AI Assistant's
 * context snapshot, T-074): the latest stored indicators of the series, the nearest levels, the setup score under the
 * Trader's preset and, on Futures, the current mark price and funding rate. The component scores are {@code null}
 * when the Trader's plan withholds them, exactly as the screen withholds them.
 *
 * <p>Rule: UC-10, UC-11, BR-12, BR-13, BR-14, BR-51; TECHNICAL_DESIGN 7.4, 7.14.
 *
 * @param symbol the pair, e.g. {@code BTCUSDT}
 * @param market the market
 * @param timeframe the series, as BR-08 spells it
 * @param openTime the open time of the candle the indicators are for, or {@code null} when none is stored
 * @param lastPrice the current last price, or {@code null} when the cache holds none
 * @param indicators the indicators of that candle; a value is {@code null} while its window is not full
 * @param nearestSupport the nearest support below the price, or {@code null}
 * @param nearestResistance the nearest resistance above the price, or {@code null}
 * @param componentsLocked whether the plan withholds the component scores
 * @param components the component scores, or {@code null} when locked or not stored
 * @param setupScore the setup score, or {@code null} when it cannot be read
 * @param preset the style preset the score was weighted under
 * @param dominantSide the winning side of the trend on Futures, market information only; {@code null} on Spot
 * @param scoredSide the side the setup score was computed for (on Futures the MACD histogram decides it when the
 *     trend is NEUTRAL), context for the score and not a trade recommendation (BR-51, D-146); {@code null} on rows
 *     scored before it was stored
 * @param capReason {@code LOW_ROOM} when the score was capped for too little room to the next level, else {@code null}
 * @param markPrice the Futures mark price, or {@code null}
 * @param fundingRate the Futures funding rate as a fraction (0.0001 = 0.01 %), or {@code null}
 */
public record PairAnalysis(
        String symbol,
        MarketType market,
        String timeframe,
        Instant openTime,
        BigDecimal lastPrice,
        Indicators indicators,
        BigDecimal nearestSupport,
        BigDecimal nearestResistance,
        boolean componentsLocked,
        Components components,
        Integer setupScore,
        SetupStyle preset,
        DominantSide dominantSide,
        ScoredSide scoredSide,
        CapReason capReason,
        BigDecimal markPrice,
        BigDecimal fundingRate) {

    public PairAnalysis {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(timeframe, "timeframe");
        Objects.requireNonNull(indicators, "indicators");
    }

    /** The stored indicators of one candle (BR-12). */
    public record Indicators(
            BigDecimal sma20,
            BigDecimal ema20,
            BigDecimal ema50,
            BigDecimal ema200,
            BigDecimal rsi14,
            BigDecimal macdLine,
            BigDecimal macdSignal,
            BigDecimal macdHistogram,
            BigDecimal bbUpper,
            BigDecimal bbMiddle,
            BigDecimal bbLower,
            BigDecimal volumeSma20) {}

    /** The component scores 0–100 the setup score is weighted from (BR-13); {@code derivatives} only on Futures. */
    public record Components(
            BigDecimal trend, BigDecimal momentum, BigDecimal volume, BigDecimal level, BigDecimal derivatives) {}
}
