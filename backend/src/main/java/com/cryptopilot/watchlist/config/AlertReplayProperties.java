package com.cryptopilot.watchlist.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The replay of the prices PRICE alerts missed while a market's stream was lost or the application was down,
 * {@code cryptopilot.alert.replay} (T-111, D-87).
 *
 * <p>Rule: NSF-06, NSF-02, BR-20; TECHNICAL_DESIGN 7.9; D-87.
 *
 * @param enabled whether missed prices are replayed; off without a profile, like the engine
 * @param window how far back a replay reaches: a missed price older than this is skipped, never fired late, since an
 *     alert about a move that long ago no longer helps the Trader act on it
 * @param startDelay how long after start-up the replay of the restart gap begins, so the engine has loaded its alerts
 *     and the exchange client is ready
 * @param maxPages the most pages of 1-minute candles fetched for one pair in one replay
 */
@Validated
@ConfigurationProperties("cryptopilot.alert.replay")
public record AlertReplayProperties(
        @DefaultValue("false") boolean enabled,

        @NotNull @DurationMin(minutes = 1) @DefaultValue("15m")
        Duration window,

        @NotNull @DefaultValue("30s") Duration startDelay,
        @Min(1) @Max(100) @DefaultValue("5") int maxPages) {}
