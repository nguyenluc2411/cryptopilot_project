package com.cryptopilot.market.client;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Where the Crypto Fear &amp; Greed Index is read, under {@code cryptopilot.market.fear-greed}. Alternative.me publishes
 * it once a day, free and without a key. Off in the base configuration and on in the {@code dev} and {@code prod}
 * profiles, so a test context never calls it.
 *
 * @param enabled whether it is read at all
 * @param baseUrl the API root, e.g. {@code https://api.alternative.me}
 * @param connectTimeout how long to wait for a connection
 * @param readTimeout how long to wait for an answer
 */
@Validated
@ConfigurationProperties("cryptopilot.market.fear-greed")
public record FearGreedProperties(
        @DefaultValue("false") boolean enabled,
        @NotNull @DefaultValue("https://api.alternative.me") URI baseUrl,
        @NotNull @DefaultValue("5s") Duration connectTimeout,
        @NotNull @DefaultValue("10s") Duration readTimeout) {}
