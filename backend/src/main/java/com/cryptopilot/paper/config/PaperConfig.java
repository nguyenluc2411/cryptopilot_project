package com.cryptopilot.paper.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the paper trading settings, so an invalid grant, fee or matching setting stops the application at start-up.
 *
 * <p>Rule: TR-02, TR-04; Q-T5.
 */
@Configuration
@EnableConfigurationProperties({PaperAccountProperties.class, PaperOrderProperties.class, PaperMatchingProperties.class
})
public class PaperConfig {}
