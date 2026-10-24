package com.cryptopilot.news.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** The settings of the crawl of NSF-10. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NewsCrawlProperties.class)
public class NewsCrawlConfig {}
