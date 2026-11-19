package com.cryptopilot.market.config;

import com.cryptopilot.market.client.fallback.OkxClient;
import com.cryptopilot.market.client.fallback.OkxProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * The display-only fallback prices of a market whose exchange data is lost (D-158): the OKX client exists only when
 * {@code cryptopilot.market.fallback.enabled} is on; off, a lost market shows no price.
 *
 * <p>Rule: BR-09; D-158.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OkxProperties.class)
public class FallbackPriceConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "cryptopilot.market.fallback", name = "enabled", havingValue = "true")
    OkxClient okxClient(OkxProperties properties, JsonMapper json) {
        return new OkxClient(properties, json);
    }
}
