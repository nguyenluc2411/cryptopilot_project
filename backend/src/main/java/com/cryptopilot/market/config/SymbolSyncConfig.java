package com.cryptopilot.market.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's task scheduler, which the NSF-01 and NSF-02 jobs schedule themselves on, and binds
 * {@code cryptopilot.market.sync} and {@code cryptopilot.market.backfill}.
 *
 * <p>Plain Spring scheduling, no Quartz and no distributed lock: the backend runs as one instance
 * (TECHNICAL_DESIGN 1.3 — ShedLock only if a second instance is ever added), the same assumption the
 * in-memory session cache of the auth module makes. A second instance would run the daily synchronisation
 * twice; it is idempotent, so the cost would be a doubled read of the exchange, not a wrong table.
 *
 * <p>Rule: NSF-01; TECHNICAL_DESIGN 1.3.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({SymbolSyncProperties.class, CandleBackfillProperties.class})
public class SymbolSyncConfig {}
