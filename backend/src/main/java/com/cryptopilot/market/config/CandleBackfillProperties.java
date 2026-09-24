package com.cryptopilot.market.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How NSF-02 backfills candles, under {@code cryptopilot.market.backfill}.
 *
 * <p>Off by default and on in the {@code dev} and {@code prod} profiles, as NSF-01 is (D-39). The depths are
 * D-41's: NSF-02 names none, so each is long enough for the 200 closed candles NSF-05 needs and short enough
 * to keep the initial load near 400,000 rows for the demo pairs. The page sizes are the cheapest per candle
 * by the weights measured on 2026-09-24 (TECHNICAL_DESIGN 7.1.1): Spot 1,000 for weight 2, futures 500 for
 * weight 2 (1,000 costs 5).
 *
 * <p>Rule: NSF-02; BR-08; D-41.
 *
 * @param enabled whether the job is scheduled at all
 * @param cron when the catch-up run happens, six-field Spring cron (one minute past each hour)
 * @param zone the zone the cron is read in
 * @param budgetSharePercent the share of a venue's per-minute weight the backfill may use before it pauses
 * @param depth how far back a series with no candle starts
 * @param pageSize candles requested per call, per venue
 */
@Validated
@ConfigurationProperties("cryptopilot.market.backfill")
public record CandleBackfillProperties(
        @DefaultValue("false") boolean enabled,
        @NotBlank @DefaultValue("0 1 * * * *") String cron,
        @NotNull @DefaultValue("UTC") ZoneId zone,
        @Min(1) @Max(80) @DefaultValue("50") int budgetSharePercent,
        @NotNull @Valid @DefaultValue Depth depth,
        @NotNull @Valid @DefaultValue PageSize pageSize) {

    /**
     * How far back each stored timeframe starts (BR-08: 15m, 1h, 4h, 1d).
     *
     * @param fifteenMinutes 60 days: 5,760 candles
     * @param oneHour 180 days: 4,320 candles
     * @param fourHours 730 days: 4,380 candles
     * @param oneDay 1,000 days: 1,000 candles
     */
    public record Depth(
            @NotNull @DefaultValue("60d") Duration fifteenMinutes,
            @NotNull @DefaultValue("180d") Duration oneHour,
            @NotNull @DefaultValue("730d") Duration fourHours,
            @NotNull @DefaultValue("1000d") Duration oneDay) {}

    /**
     * Candles per call.
     *
     * @param spot Spot, at most 1,000 (weight 2 whatever the limit)
     * @param futures futures, at most 1,500 (weight 2 up to 500, 5 up to 1,000, 10 above)
     */
    public record PageSize(
            @Min(1) @Max(1000) @DefaultValue("1000") int spot,
            @Min(1) @Max(1500) @DefaultValue("500") int futures) {}
}
