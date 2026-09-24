package com.cryptopilot.market.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * When NSF-01 runs and which symbols it may bring in, under {@code cryptopilot.market.sync}.
 *
 * <p>{@code enabled} is off in the base configuration and on in the {@code dev} and {@code prod} profiles, so
 * a test context — which runs without a profile — never schedules a job that would call the real exchange.
 *
 * <p>The seed symbols are the only way a pair enters the table without an administrator: a symbol listed
 * here and missing from {@code crypto_pair} is created, INACTIVE, the first time a market lists it. Empty
 * until the demo pair list (Q-05) is decided; the synchronisation otherwise only updates pairs that exist.
 *
 * <p>Rule: NSF-01; BR-07; TECHNICAL_DESIGN 1.3 (Spring {@code @Scheduled}, single instance).
 *
 * @param enabled whether the job is scheduled at all
 * @param cron when the daily run happens, six-field Spring cron (NSF-01: 00:05)
 * @param zone the zone the cron is read in (NSF-01: UTC)
 * @param seedSymbols pair symbols that may be created if missing, e.g. {@code BTCUSDT}
 */
@Validated
@ConfigurationProperties("cryptopilot.market.sync")
public record SymbolSyncProperties(
        @DefaultValue("false") boolean enabled,
        @NotBlank @DefaultValue("0 5 0 * * *") String cron,
        @NotNull @DefaultValue("UTC") ZoneId zone,
        @NotNull @DefaultValue List<String> seedSymbols) {}
