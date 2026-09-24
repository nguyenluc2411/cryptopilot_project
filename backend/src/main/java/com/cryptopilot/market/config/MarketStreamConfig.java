package com.cryptopilot.market.config;

import com.cryptopilot.market.client.BinanceStreamClient;
import com.cryptopilot.market.client.BinanceStreamProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes the Binance stream client, built from {@code cryptopilot.market.stream}.
 *
 * <p>Building it opens nothing: connections are opened by the stream supervisor when the application is ready
 * and the streams are enabled, so a test context or an application started where the exchange is unreachable
 * starts all the same. Its HTTP client and timer thread are closed with the context.
 *
 * <p>Rule: NSF-03; BR-09; TECHNICAL_DESIGN 7.1.
 */
@Configuration
@EnableConfigurationProperties(BinanceStreamProperties.class)
public class MarketStreamConfig {

    /** The stream client, closed with the application context. */
    @Bean(destroyMethod = "close")
    BinanceStreamClient binanceStreamClient(BinanceStreamProperties properties, JsonMapper json, Clock clock) {
        return new BinanceStreamClient(properties, json, clock);
    }
}
