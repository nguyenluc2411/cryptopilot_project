package com.cryptopilot.market.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the properties of the latest-price cache and the market topics (T-022).
 *
 * <p>Rule: NSF-03; TECHNICAL_DESIGN 5.6 and 9.
 */
@Configuration
@EnableConfigurationProperties({PriceCacheProperties.class, MarketHubProperties.class})
public class MarketRealtimeConfig {}
