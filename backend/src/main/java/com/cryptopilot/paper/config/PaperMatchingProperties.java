package com.cryptopilot.paper.config;

import com.cryptopilot.paper.model.enums.LimitFillMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The paper matching engine, {@code cryptopilot.paper.matching}.
 *
 * <p>Rule: TR-02, TR-05; NSF-07; Q-T6, Q-T8; A-04.
 *
 * @param enabled whether working LIMIT orders are matched at all
 * @param limitFillMode when a trade at exactly a waiting order's price fills it (Q-T8): {@code QUEUE} once the
 *     quantity ahead of it there is traded, as Binance's price-time priority does; {@code CROSS} never, only a price
 *     through it; {@code TOUCH} always
 * @param tradeSilence how long after a pair's last market trade its forming 1-minute candles fill orders again: while
 *     its trades flow they fill orders at once, and a lost trade stream falls back to the candles
 * @param retryDelay how long a fill that failed waits before the pair's next price update tries it again
 * @param retryDeadline how long after its first failure a fill is given up; the order then stays NEW and waits in the
 *     book again, and the pair's watermark is held so a restart replays the candle that reached it
 * @param fillParallelism how many Traders' fills of one range are stored at once; the fills of one Trader stay in
 *     priority order. Keep it below the connection pool, which the order placements share
 * @param stopTimeout how long a stop waits for the engine's threads to end
 * @param replay the replay of the closed 1-minute candles missed while the engine was down or the stream was lost
 */
@Validated
@ConfigurationProperties("cryptopilot.paper.matching")
public record PaperMatchingProperties(
        @DefaultValue("true") boolean enabled,
        @NotNull @DefaultValue("QUEUE") LimitFillMode limitFillMode,
        @NotNull @DefaultValue("5s") Duration tradeSilence,
        @NotNull @DefaultValue("2s") Duration retryDelay,
        @NotNull @DefaultValue("10m") Duration retryDeadline,
        @Min(1) @DefaultValue("8") int fillParallelism,
        @NotNull @DefaultValue("10s") Duration stopTimeout,
        @NotNull @Valid @DefaultValue Replay replay) {

    /**
     * The replay of missed candles (Q-T6). Off by default and on in the {@code dev} and {@code prod} profiles, like the
     * plan engine's, so a test context never calls the exchange.
     *
     * @param enabled whether missed candles are fetched and replayed
     * @param maxWindow how far back a replay may start; older candles are not replayed and the skipped interval is
     *     logged
     * @param retryDelay how long a replay the exchange refused or that failed waits before it is tried again
     */
    public record Replay(
            @DefaultValue("false") boolean enabled,
            @NotNull @DefaultValue("24h") Duration maxWindow,
            @NotNull @DefaultValue("5s") Duration retryDelay) {}
}
