package com.cryptopilot.watchlist.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the alert engine's settings.
 *
 * <p>Rule: NSF-06; TECHNICAL_DESIGN 7.9.
 */
@Configuration
@EnableConfigurationProperties(AlertEngineProperties.class)
public class AlertEngineConfig {}
