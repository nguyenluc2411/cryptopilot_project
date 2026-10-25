package com.cryptopilot.ai.config;

import com.cryptopilot.ai.client.AiServiceClient;
import com.cryptopilot.ai.client.AiServiceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes the one AI Service client, built from {@code cryptopilot.ai.service}. Nothing is called at start-up, so the
 * application starts where the AI Service is not running; the HTTP client is closed with the context.
 *
 * <p>Rule: ADR-004; TECHNICAL_DESIGN 7.14.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiServiceProperties.class)
public class AiServiceConfig {

    @Bean(destroyMethod = "close")
    AiServiceClient aiServiceClient(AiServiceProperties properties, JsonMapper json) {
        return new AiServiceClient(properties, json);
    }
}
