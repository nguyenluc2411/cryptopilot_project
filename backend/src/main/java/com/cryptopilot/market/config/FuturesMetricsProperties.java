package com.cryptopilot.market.config;

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
 * How NSF-04 collects futures metrics, under {@code cryptopilot.market.futures-metrics}.
 *
 * <p>Off by default and on in the {@code dev} and {@code prod} profiles, as the other ingestion jobs are (D-39),
 * so a test context never calls the exchange. The limits are the documented ones, verified 2026-09-25
 * (TECHNICAL_DESIGN 7.1.1): the open interest and long/short histories answer at most 500 periods per call and
 * 1000 calls per 5 minutes per IP, and keep the latest month; settled funding rates answer at most 1000 per
 * call and share 500 calls per 5 minutes per IP with the funding settings.
 *
 * <p>Rule: NSF-04; BR-10, BR-11.
 *
 * @param enabled whether the job is scheduled at all
 * @param cron when a run happens, six-field Spring cron: every 5 minutes, 90 seconds after the 5-minute instant,
 *     when the exchange has published the period that just ended
 * @param zone the zone the cron is read in
 * @param depth how far back a pair with no metric stored starts; inside the month the exchange keeps (BR-10)
 * @param pageSize periods requested per call, at most the exchange's 500
 * @param maxRequestsPerRun history calls one run may make, well under the exchange's 1000 per 5 minutes; a run
 *     that reaches it stops and the next run continues from what was stored
 * @param settlementDepth how far back the settled funding rates of a pair with none stored start
 * @param markPriceMaxAge how old the streamed next funding time may be before it is read from the REST source
 */
@Validated
@ConfigurationProperties("cryptopilot.market.futures-metrics")
public record FuturesMetricsProperties(
        @DefaultValue("false") boolean enabled,
        @NotBlank @DefaultValue("30 1/5 * * * *") String cron,
        @NotNull @DefaultValue("UTC") ZoneId zone,
        @NotNull @DefaultValue("30d") Duration depth,
        @Min(1) @Max(500) @DefaultValue("500") int pageSize,
        @Min(1) @Max(900) @DefaultValue("400") int maxRequestsPerRun,
        @NotNull @DefaultValue("30d") Duration settlementDepth,
        @NotNull @DefaultValue("2m") Duration markPriceMaxAge) {}
