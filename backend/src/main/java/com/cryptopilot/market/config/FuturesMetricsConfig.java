package com.cryptopilot.market.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code cryptopilot.market.futures-metrics} for NSF-04.
 *
 * <p>Rule: NSF-04.
 */
@Configuration
@EnableConfigurationProperties(FuturesMetricsProperties.class)
public class FuturesMetricsConfig {}
