package com.cryptopilot.trading.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The partitions of the matching engine, {@code cryptopilot.trading.matching}.
 *
 * <p>Rule: NSF-07; TECHNICAL_DESIGN 7.7 and 10.
 *
 * @param enabled whether the engine runs at all
 * @param partitions how many single-consumer partitions share the pairs
 * @param queueCapacity commands a partition may hold; a price update beyond it waits merged with the other updates of
 *     its pair's candle, never dropped
 * @param retry how a failing fill is tried again
 * @param pendingWarnThreshold how many pending ranges a pair may hold before one warning is logged
 * @param stopTimeout how long a stop waits for the consumers to end; a start never runs beside one still running
 * @param replay the restart replay of the closed 1-minute candles missed while the engine was down
 */
@Validated
@ConfigurationProperties("cryptopilot.trading.matching")
public record MatchingProperties(
        @DefaultValue("true") boolean enabled,
        @Min(1) @Max(64) @DefaultValue("4") int partitions,
        @Min(1) @DefaultValue("10000") int queueCapacity,
        @NotNull @Valid @DefaultValue Retry retry,
        @Min(1) @DefaultValue("60") int pendingWarnThreshold,
        @NotNull @DefaultValue("10s") Duration stopTimeout,
        @NotNull @Valid @DefaultValue Replay replay) {

    /**
     * The retries of a failing fill: exponential back-off with jitter, until a deadline counted from the first failure.
     * A retry runs only when the pair's next price update arrives after it is due (about every 2 s on
     * {@code kline_1m}); no timer fires it, so a pair without updates waits.
     *
     * @param initialDelay the wait after the first failure
     * @param maxDelay the longest wait between two tries
     * @param jitterPercent the largest share of a wait taken off at random
     * @param deadline how long after the first failure the fill is given up; the plan then stays ACTIVE
     */
    public record Retry(
            @NotNull @DefaultValue("1s") Duration initialDelay,
            @NotNull @DefaultValue("30s") Duration maxDelay,
            @Min(0) @Max(100) @DefaultValue("20") int jitterPercent,
            @NotNull @DefaultValue("10m") Duration deadline) {}

    /**
     * The restart replay (A-04). Off by default and on in the {@code dev} and {@code prod} profiles, like the market
     * streams, so a test context never calls the exchange; when off, every pair goes live at start without a replay.
     *
     * @param enabled whether missed candles are fetched and replayed at start
     * @param maxWindow how far back a replay may start; candles older than now minus this window are not replayed and
     *     the skipped interval is logged (Q-34)
     * @param catchUpWait how long to wait before asking again for a candle that closed while the replay ran but is not
     *     served yet
     * @param catchUpAttempts how many times to ask again before the pair goes live with the hole logged
     * @param maxBufferedMinutes how many minutes of live updates a pair keeps while its replay runs; the oldest is
     *     dropped beyond it, with a warning
     */
    public record Replay(
            @DefaultValue("false") boolean enabled,
            @NotNull @DefaultValue("24h") Duration maxWindow,
            @NotNull @DefaultValue("2s") Duration catchUpWait,
            @Min(0) @DefaultValue("3") int catchUpAttempts,
            @Min(2) @DefaultValue("1440") int maxBufferedMinutes) {}
}
