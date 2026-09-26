package com.cryptopilot.market.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the setup score presets (T-027), so an invalid preset stops the application at start-up.
 *
 * <p>Rule: BR-13; D-53.
 */
@Configuration
@EnableConfigurationProperties(SetupScoreProperties.class)
public class SetupScoreConfig {}
