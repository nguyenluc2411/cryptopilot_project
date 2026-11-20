package com.cryptopilot.market.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code cryptopilot.market.health} for the data health of each market.
 *
 * <p>Rule: NSF-03; D-159.
 */
@Configuration
@EnableConfigurationProperties(MarketHealthProperties.class)
public class MarketHealthConfig {}
