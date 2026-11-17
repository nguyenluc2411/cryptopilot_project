package com.cryptopilot.market.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The market list of coins (SCR-09): every coin the listed pairs trade, with what the live tickers do not carry, and
 * the figures of the whole market above it. The live price, the 24-hour change and the volume come from the ticker
 * topics; the page computes the market cap and the 1-hour and 7-day changes from the live price and the figures here,
 * so they move with it.
 *
 * <p>A figure not known (a reference service not read yet, a series without candles that far back) is absent, never
 * zero.
 *
 * <p>Rule: SRS 3.3.1; UC-09.
 *
 * @param asOf when these figures were computed
 * @param global the whole crypto market, from CoinPaprika, or absent
 * @param fearGreed the Crypto Fear &amp; Greed Index, from alternative.me, or absent
 * @param altcoinSeason how many listed altcoins beat Bitcoin over 90 days, or absent
 * @param openInterest the open interest of the listed Futures pairs, or absent
 * @param coins the listed coins, by CoinPaprika rank, then symbol
 */
public record CoinMarketResponse(
        Instant asOf,
        GlobalMarket global,
        FearGreed fearGreed,
        AltcoinSeason altcoinSeason,
        OpenInterest openInterest,
        List<CoinRow> coins) {

    /**
     * The whole crypto market.
     *
     * @param marketCapUsd its cap in US dollars
     * @param marketCapChange24hPercent the change of the cap over 24 hours, in percent, or absent
     * @param volume24hUsd the volume of 24 hours in US dollars, or absent
     * @param bitcoinDominancePercent Bitcoin's share of the cap, in percent, or absent
     * @param asOf when CoinPaprika computed it, or absent
     */
    public record GlobalMarket(
            BigDecimal marketCapUsd,
            BigDecimal marketCapChange24hPercent,
            BigDecimal volume24hUsd,
            BigDecimal bitcoinDominancePercent,
            Instant asOf) {}

    /**
     * The Crypto Fear &amp; Greed Index.
     *
     * @param value 0 (extreme fear) to 100 (extreme greed)
     * @param classification the published label, e.g. {@code Extreme Fear}
     * @param asOf the day it stands for
     */
    public record FearGreed(int value, String classification, Instant asOf) {}

    /**
     * The altcoin season of the listed coins: the share of the listed altcoins (stablecoins left out) whose price
     * rose more than Bitcoin's over {@code days}. 75 or more is an altcoin season, 25 or less a Bitcoin season.
     *
     * @param index 0 to 100
     * @param altcoins how many altcoins were compared
     * @param days the period, in days
     */
    public record AltcoinSeason(int index, int altcoins, int days) {}

    /**
     * The open interest of the listed Futures pairs together.
     *
     * @param valueUsd the latest, in US dollars
     * @param change24hPercent its change over 24 hours, in percent, or absent
     * @param lastDay one point an hour over the last 24 hours, oldest first
     */
    public record OpenInterest(BigDecimal valueUsd, BigDecimal change24hPercent, List<Point> lastDay) {}

    /**
     * One point of a series.
     *
     * @param at the instant
     * @param valueUsd the value in US dollars
     */
    public record Point(Instant at, BigDecimal valueUsd) {}

    /**
     * One listed coin.
     *
     * @param symbol the coin, e.g. {@code BTC}
     * @param name its name
     * @param logoUrl where its logo is, or absent
     * @param rank its rank by market cap on CoinPaprika, or absent
     * @param spotSymbol its pair enabled on Spot, e.g. {@code BTCUSDT}, or absent
     * @param futuresSymbol its pair enabled on Futures, or absent
     * @param circulatingSupply the coins in circulation, from CoinPaprika, or absent
     * @param price1hAgo its price an hour ago, from the stored candles, or absent
     * @param price7dAgo its price seven days ago, or absent
     * @param sparkline7d its hourly closes of the last seven days, oldest first
     */
    public record CoinRow(
            String symbol,
            String name,
            String logoUrl,
            Integer rank,
            String spotSymbol,
            String futuresSymbol,
            BigDecimal circulatingSupply,
            BigDecimal price1hAgo,
            BigDecimal price7dAgo,
            List<BigDecimal> sparkline7d) {}
}
