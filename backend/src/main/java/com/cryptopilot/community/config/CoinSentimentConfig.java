package com.cryptopilot.community.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code cryptopilot.community.coin-sentiment} (D-125). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CoinSentimentProperties.class)
public class CoinSentimentConfig {}
