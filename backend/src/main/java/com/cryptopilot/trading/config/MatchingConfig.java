package com.cryptopilot.trading.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link MatchingProperties}, {@link ReplayLockProperties} and {@link PlanExpiryProperties}. */
@Configuration
@EnableConfigurationProperties({MatchingProperties.class, ReplayLockProperties.class, PlanExpiryProperties.class})
public class MatchingConfig {}
