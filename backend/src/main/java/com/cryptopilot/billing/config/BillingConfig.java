package com.cryptopilot.billing.config;

import com.cryptopilot.billing.client.MoMoProperties;
import com.cryptopilot.billing.client.SePayProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * The billing module's settings.
 *
 * <p>Rule: TECHNICAL_DESIGN 7.11; D-147.
 */
@Configuration
@EnableConfigurationProperties({
    BillingProperties.class,
    SePayProperties.class,
    MoMoProperties.class,
    ReconcileProperties.class,
    SubscriptionSweepProperties.class
})
class BillingConfig {}
