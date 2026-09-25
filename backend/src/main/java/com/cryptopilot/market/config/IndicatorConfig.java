package com.cryptopilot.market.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the properties of the incremental indicators (T-025).
 *
 * <p>Rule: BR-12; TECHNICAL_DESIGN 7.2.
 */
@Configuration
@EnableConfigurationProperties(IndicatorProperties.class)
public class IndicatorConfig {}
