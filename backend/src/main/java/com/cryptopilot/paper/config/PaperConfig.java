package com.cryptopilot.paper.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the paper trading settings, so an invalid grant stops the application at start-up.
 *
 * <p>Rule: TR-04; Q-T5.
 */
@Configuration
@EnableConfigurationProperties(PaperAccountProperties.class)
public class PaperConfig {}
