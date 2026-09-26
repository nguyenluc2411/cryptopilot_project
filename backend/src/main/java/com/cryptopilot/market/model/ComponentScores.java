package com.cryptopilot.market.model;

import com.cryptopilot.market.MarketType;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * The component scores of one closed candle of one series, 0–100 at scale 2; a {@code null} score could not be
 * computed. These are what is stored; the setup score is derived from them when read.
 *
 * <p>Rule: BR-13; TECHNICAL_DESIGN 7.4; D-53.
 *
 * @param market the market of the series
 * @param timeframe the timeframe of the series
 * @param formulaVersion the version of the component formulas
 * @param trend the trend component
 * @param momentum the momentum component
 * @param volume the volume component
 * @param level the level component
 * @param derivatives the derivatives component; always {@code null} on Spot
 */
public record ComponentScores(
        MarketType market,
        String timeframe,
        String formulaVersion,
        BigDecimal trend,
        BigDecimal momentum,
        BigDecimal volume,
        BigDecimal level,
        BigDecimal derivatives) {

    public ComponentScores {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(timeframe, "timeframe");
        Objects.requireNonNull(formulaVersion, "formulaVersion");
        if (market == MarketType.SPOT && derivatives != null) {
            throw new IllegalArgumentException("Spot has no derivatives component");
        }
    }
}
