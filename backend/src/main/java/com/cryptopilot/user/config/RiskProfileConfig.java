package com.cryptopilot.user.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the risk profile parameters (T-103), so an invalid profile stops the application at start-up.
 *
 * <p>Rule: BR-66; D-53.
 */
@Configuration
@EnableConfigurationProperties(RiskProfileProperties.class)
public class RiskProfileConfig {}
