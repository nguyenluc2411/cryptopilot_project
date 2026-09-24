package com.cryptopilot.market.config;

import com.cryptopilot.market.client.BinanceBanStore;
import com.cryptopilot.market.client.BinanceClientProperties;
import com.cryptopilot.market.client.BinanceRestClient;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes the one Binance REST client, built from {@code cryptopilot.market.binance}.
 *
 * <p>The ban store is the database one ({@code PersistentBinanceBans}), so an IP ban survives a restart.
 *
 * <p>Nothing is called at start-up: the client opens no connection until a job asks it for data, so an
 * application started where the exchange is unreachable still starts, and the failure belongs to the job
 * that needed the data. The HTTP client is closed with the context.
 *
 * <p>Rule: BR-09; TECHNICAL_DESIGN 7.1.2.
 */
@Configuration
@EnableConfigurationProperties(BinanceClientProperties.class)
public class BinanceClientConfig {

    /** The client, closed with the application context. */
    @Bean(destroyMethod = "close")
    BinanceRestClient binanceRestClient(
            BinanceClientProperties properties, Clock clock, JsonMapper json, BinanceBanStore bans) {
        return new BinanceRestClient(properties, clock, json, bans);
    }
}
