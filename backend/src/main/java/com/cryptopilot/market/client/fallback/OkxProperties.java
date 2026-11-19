package com.cryptopilot.market.client.fallback;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Where the display-only fallback prices come from while a market's exchange data is lost (D-158), under
 * {@code cryptopilot.market.fallback}: OKX's public market API, which needs no key.
 *
 * <p>A fallback price is shown to the Traders, labelled with its source, and nothing else: it never fills, triggers,
 * liquidates or charges anything, and is never stored (BR-09). It is read only while a market is paused or recovering.
 *
 * <p>{@code enabled} is off in the base configuration and on in the {@code dev} and {@code prod} profiles, so a test
 * context never calls the real service.
 *
 * @param enabled whether fallback prices are read at all
 * @param baseUrl the API root, e.g. {@code https://www.okx.com}
 * @param connectTimeout how long to wait for a connection
 * @param readTimeout how long one call may take, body included
 * @param pollInterval how often the prices of a closed market are read again
 * @param divergence how far, as a fraction, a fallback price may be from the exchange's last one before it is flagged
 *     as possibly far from it
 * @param overrides OKX instrument ids by exchange symbol and market, for a pair OKX lists under another name, e.g.
 *     {@code FUTURES.1000PEPEUSDT: PEPE-USDT-SWAP}; the keys are {@code <MARKET>.<SYMBOL>}
 */
@Validated
@ConfigurationProperties("cryptopilot.market.fallback")
public record OkxProperties(
        @DefaultValue("false") boolean enabled,

        @NotNull @DefaultValue("https://www.okx.com") URI baseUrl,

        @NotNull @DefaultValue("5s") Duration connectTimeout,
        @NotNull @DefaultValue("5s") Duration readTimeout,
        @NotNull @DefaultValue("3s") Duration pollInterval,
        @NotNull @DefaultValue("0.05") BigDecimal divergence,
        @DefaultValue Map<String, String> overrides) {

    public OkxProperties {
        Map<String, String> byKey = new TreeMap<>();
        if (overrides != null) {
            overrides.forEach((key, instrument) -> {
                if (key != null && instrument != null && !instrument.isBlank()) {
                    byKey.put(
                            key.strip().toUpperCase(Locale.ROOT),
                            instrument.strip().toUpperCase(Locale.ROOT));
                }
            });
        }
        overrides = Map.copyOf(byKey);
        if (divergence != null && divergence.signum() <= 0) {
            throw new IllegalArgumentException("divergence must be above zero");
        }
    }
}
