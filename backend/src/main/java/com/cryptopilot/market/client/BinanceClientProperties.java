package com.cryptopilot.market.client;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where the exchange is and how hard the client may lean on it, under
 * {@code cryptopilot.market.binance}.
 *
 * <p>Nothing about the exchange is a literal in code: the hosts and the per-minute weight budgets are
 * the documented values of TECHNICAL_DESIGN 7.1.1 set in {@code application.yml}, so a changed limit is
 * a configuration change and a test points the client at a local server by setting two URLs.
 *
 * <p>Validated at start-up: a budget of zero, a missing host or a pause ratio above one would make the
 * client refuse everything or nothing, and either is better found when the application starts than
 * when the first backfill runs.
 *
 * <p>Rule: BR-09; TECHNICAL_DESIGN 1.3, 7.1.1 and 7.1.2.
 *
 * @param spot the Spot API
 * @param futures the USDⓈ-M futures API
 * @param connectTimeout how long to wait for a connection
 * @param readTimeout how long to wait for a response once connected
 * @param weightPausePercent the share of the weight budget, in percent, at which the client stops
 *     calling until the next minute (TECHNICAL_DESIGN 7.1 step 5: 80)
 * @param defaultRetryAfter how long to wait after a 429 or 418 that carries no {@code Retry-After}
 * @param retry the retry of transient failures
 * @param circuitBreaker the breaker that stops calling a venue that keeps failing
 */
@Validated
@ConfigurationProperties("cryptopilot.market.binance")
public record BinanceClientProperties(
        @NotNull @Valid Venue spot,
        @NotNull @Valid Venue futures,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @Min(1) @Max(100) int weightPausePercent,
        @NotNull Duration defaultRetryAfter,
        @NotNull @Valid Retry retry,
        @NotNull @Valid CircuitBreaker circuitBreaker) {

    /**
     * One of the two APIs.
     *
     * @param baseUrl the REST host, e.g. {@code https://api.binance.com}
     * @param requestWeightPerMinute the documented {@code REQUEST_WEIGHT} budget per minute and IP
     */
    public record Venue(@NotNull URI baseUrl, @Min(1) int requestWeightPerMinute) {}

    /**
     * Retry of a 5xx, a timeout or a connection failure. Never of a 4xx: a 429 or 418 is waited out,
     * any other 4xx will fail again.
     *
     * @param maxRetries retries after the first attempt
     * @param initialDelay the first back-off
     * @param multiplier the growth of each back-off
     * @param maxDelay the longest back-off
     * @param jitter the random spread added to each back-off, so that callers do not retry in step
     */
    public record Retry(
            @Min(0) int maxRetries,
            @NotNull Duration initialDelay,
            @DecimalMin("1.0") double multiplier,
            @NotNull Duration maxDelay,
            @NotNull Duration jitter) {}

    /**
     * The breaker: this many consecutive failed calls open it for this long.
     *
     * @param failureThreshold consecutive failed calls that open the breaker
     * @param openDuration how long it stays open before one trial call is let through
     */
    public record CircuitBreaker(
            @Min(1) int failureThreshold, @NotNull Duration openDuration) {}
}
