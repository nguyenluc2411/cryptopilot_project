package com.cryptopilot.common.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Settings of the HTTP edge that every module shares: which proxies are trusted for the client address.
 *
 * <p>Rule: NSF-18; TECHNICAL_DESIGN 5.3.
 */
@Configuration
@EnableConfigurationProperties(TrustedProxyProperties.class)
public class WebEdgeConfig {}
