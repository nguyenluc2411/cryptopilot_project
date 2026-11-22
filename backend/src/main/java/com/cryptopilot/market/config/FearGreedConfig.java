package com.cryptopilot.market.config;

import com.cryptopilot.common.config.SchedulingConfig;
import com.cryptopilot.market.client.FearGreedClient;
import com.cryptopilot.market.client.FearGreedProperties;
import com.cryptopilot.market.job.FearGreedJob;
import com.cryptopilot.market.model.MarketReferenceStore;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Crypto Fear &amp; Greed Index of the market list, only when {@code cryptopilot.market.fear-greed.enabled} is on:
 * off, no client, no job, and the market list shows no index.
 *
 * <p>Rule: SRS 3.3.1.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FearGreedProperties.class)
@ConditionalOnProperty(prefix = "cryptopilot.market.fear-greed", name = "enabled", havingValue = "true")
public class FearGreedConfig {

    @Bean(destroyMethod = "close")
    FearGreedClient fearGreedClient(FearGreedProperties properties, JsonMapper json) {
        return new FearGreedClient(properties, json);
    }

    @Bean
    FearGreedJob fearGreedJob(
            FearGreedClient client,
            MarketReferenceStore store,
            @Qualifier(SchedulingConfig.BACKGROUND) TaskScheduler scheduler,
            Clock clock) {
        return new FearGreedJob(client, store, scheduler, clock);
    }
}
