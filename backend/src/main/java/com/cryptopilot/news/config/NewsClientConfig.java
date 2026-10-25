package com.cryptopilot.news.config;

import com.cryptopilot.news.client.RssClient;
import com.cryptopilot.news.client.RssClientProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the one feed reader, built from {@code cryptopilot.news.rss}. It opens no connection until it is asked for
 * a feed, so an application started without network still starts; its HTTP client is closed with the context.
 *
 * <p>Rule: NSF-10; UC-49.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RssClientProperties.class)
public class NewsClientConfig {

    @Bean(destroyMethod = "close")
    RssClient rssClient(RssClientProperties properties) {
        return new RssClient(properties);
    }
}
