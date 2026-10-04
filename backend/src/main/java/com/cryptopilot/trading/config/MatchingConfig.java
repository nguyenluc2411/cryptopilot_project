package com.cryptopilot.trading.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link MatchingProperties}. */
@Configuration
@EnableConfigurationProperties({MatchingProperties.class, ReplayLockProperties.class})
public class MatchingConfig {}
