package com.cryptopilot.market.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code cryptopilot.market.api} for the market data API.
 *
 * <p>Rule: UC-09.
 */
@Configuration
@EnableConfigurationProperties(MarketApiProperties.class)
public class MarketApiConfig {}
