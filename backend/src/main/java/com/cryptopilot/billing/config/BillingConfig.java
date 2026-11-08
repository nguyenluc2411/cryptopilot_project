package com.cryptopilot.billing.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * The billing module's settings.
 *
 * <p>Rule: TECHNICAL_DESIGN 7.11; D-146.
 */
@Configuration
@EnableConfigurationProperties(BillingProperties.class)
class BillingConfig {}
