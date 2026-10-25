package com.cryptopilot.news.client;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * How the news module reads a feed, under {@code cryptopilot.news.rss}. Validated at start-up, so a zero timeout or an
 * empty agent is found when the application starts rather than at the first crawl.
 *
 * <p>Rule: NSF-10 (each feed is read with a 30-second timeout); UC-49 (a feed is read once before it is saved).
 *
 * @param connectTimeout how long to wait for a connection
 * @param readTimeout how long one request may take, the 30 seconds of NSF-10
 * @param userAgent the User-Agent of a source that sets none
 * @param maxFeedSize the largest response read; a feed is a list of links, and anything larger is not one
 * @param maxRedirects how many redirects are followed, each one checked like the first address
 * @param allowPrivateAddresses whether a loopback, private or link-local address may be read; off everywhere except a
 *     test that serves its feed locally, because the server must not be made to read its own network
 */
@Validated
@ConfigurationProperties("cryptopilot.news.rss")
public record RssClientProperties(
        @NotNull @DefaultValue("10s") Duration connectTimeout,
        @NotNull @DefaultValue("30s") Duration readTimeout,
        @NotBlank @DefaultValue("CryptoPilotNewsBot/1.0") String userAgent,
        @NotNull @DefaultValue("5MB") DataSize maxFeedSize,
        @Min(0) @Max(10) @DefaultValue("5") int maxRedirects,
        @DefaultValue("false") boolean allowPrivateAddresses) {}
