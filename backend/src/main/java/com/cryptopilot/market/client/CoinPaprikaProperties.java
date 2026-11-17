package com.cryptopilot.market.client;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Where CoinPaprika is and how the coins are matched to it, under {@code cryptopilot.market.coinpaprika}.
 *
 * <p>CoinPaprika gives what Binance does not: a coin's name and logo. It is reference data only, never a price, a candle
 * or a rule of a pair, which stay with Binance (BR-09); so a CoinPaprika outage leaves the coins as they are and changes
 * nothing else. The free API needs no key.
 *
 * <p>{@code enabled} is off in the base configuration and on in the {@code dev} and {@code prod} profiles, so a test
 * context never calls the real service.
 *
 * @param enabled whether the coins are described at all
 * @param baseUrl the API root, e.g. {@code https://api.coinpaprika.com/v1}
 * @param detailDelay the pause between two calls for a coin's logo, to stay well under the free rate limit
 * @param connectTimeout how long to wait for a connection
 * @param readTimeout how long a small call (one coin, the global figures) may take, body included
 * @param bodyTimeout how long a list call ({@code /coins}, {@code /tickers}, several megabytes) may take, body included
 * @param retryUnmatchedAfter how long a coin CoinPaprika had nothing more for is left alone before it is tried again;
 *     under the daily synchronisation, so a run that starts a little earlier than the day before still retries it
 * @param overrides CoinPaprika ids by Binance symbol, for a coin CoinPaprika lists under another ticker or whose ticker
 *     a larger coin also uses, e.g. {@code TON: toncoin-the-open-network} (CoinPaprika spells Toncoin {@code GRAM})
 */
@Validated
@ConfigurationProperties("cryptopilot.market.coinpaprika")
public record CoinPaprikaProperties(
        @DefaultValue("false") boolean enabled,

        @NotNull @DefaultValue("https://api.coinpaprika.com/v1")
        URI baseUrl,

        @NotNull @DefaultValue("200ms") Duration detailDelay,
        @NotNull @DefaultValue("5s") Duration connectTimeout,
        @NotNull @DefaultValue("15s") Duration readTimeout,
        @NotNull @DefaultValue("120s") Duration bodyTimeout,
        @NotNull @DefaultValue("23h") Duration retryUnmatchedAfter,
        @DefaultValue Map<String, String> overrides) {

    public CoinPaprikaProperties {
        Map<String, String> bySymbol = new TreeMap<>();
        if (overrides != null) {
            overrides.forEach((symbol, id) -> {
                if (symbol != null && id != null && !id.isBlank()) {
                    bySymbol.put(symbol.strip().toUpperCase(Locale.ROOT), id.strip());
                }
            });
        }
        overrides = Map.copyOf(bySymbol);
    }
}
