package com.cryptopilot.market.client;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The whole crypto market as CoinPaprika's {@code /global} sums it.
 *
 * @param marketCapUsd the total market cap in US dollars
 * @param marketCapChange24hPercent its change over 24 hours, in percent
 * @param volume24hUsd the traded volume of 24 hours in US dollars
 * @param bitcoinDominancePercent Bitcoin's share of the market cap, in percent
 * @param asOf when CoinPaprika computed it
 */
public record CoinPaprikaGlobal(
        BigDecimal marketCapUsd,
        BigDecimal marketCapChange24hPercent,
        BigDecimal volume24hUsd,
        BigDecimal bitcoinDominancePercent,
        Instant asOf) {}
