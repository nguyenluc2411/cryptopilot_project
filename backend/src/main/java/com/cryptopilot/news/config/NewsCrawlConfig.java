package com.cryptopilot.news.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** The settings of the crawl of NSF-10, of the summaries of NSF-11 and of the news the AI Assistant reads. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({NewsCrawlProperties.class, NewsSummaryProperties.class, NewsAssistantProperties.class})
public class NewsCrawlConfig {}
