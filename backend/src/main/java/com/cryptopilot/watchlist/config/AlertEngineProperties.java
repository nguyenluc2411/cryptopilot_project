package com.cryptopilot.watchlist.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The alert engine of NSF-06, {@code cryptopilot.alert}.
 *
 * <p>Rule: NSF-06, BR-19; SRS 3.4.4; TECHNICAL_DESIGN 7.9.
 *
 * @param enabled whether price updates are evaluated; off without a profile, so a test context starts no engine
 * @param partitions how many single-thread partitions share the pairs
 * @param queueCapacity how many pairs a partition's queue holds; beyond it a pair waits outside the queue with only
 *     its latest price kept, and nothing blocks the stream
 * @param throttle the shortest time between two evaluations of one pair (SRS 3.4.4: at most once per second)
 * @param staleness how old the previous price of a pair may be when its stream reconnects before a cross is no
 *     longer measured from it
 * @param stopTimeout how long a stop waits for the partitions to end
 * @param expirySweep the job that sets alerts past their expiry EXPIRED
 */
@Validated
@ConfigurationProperties("cryptopilot.alert")
public record AlertEngineProperties(
        @DefaultValue("false") boolean enabled,
        @Min(1) @Max(64) @DefaultValue("2") int partitions,
        @Min(1) @DefaultValue("1000") int queueCapacity,
        @NotNull @DefaultValue("1s") Duration throttle,
        @NotNull @DefaultValue("5m") Duration staleness,
        @NotNull @DefaultValue("5s") Duration stopTimeout,
        @NotNull @Valid @DefaultValue ExpirySweep expirySweep) {

    /**
     * The expiry sweep (BR-19: alerts are not evaluated after their expiry).
     *
     * @param enabled whether it is scheduled; off without a profile
     * @param cron when it runs
     * @param zone the zone of the cron expression
     */
    public record ExpirySweep(
            @DefaultValue("false") boolean enabled,
            @NotBlank @DefaultValue("0 * * * * *") String cron,
            @NotBlank @DefaultValue("UTC") String zone) {}
}
