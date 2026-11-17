package com.cryptopilot.market.config;

import com.cryptopilot.market.client.CoinPaprikaClient;
import com.cryptopilot.market.client.CoinPaprikaProperties;
import com.cryptopilot.market.job.CoinDescriptionJob;
import com.cryptopilot.market.job.MarketReferenceJob;
import com.cryptopilot.market.model.MarketReferenceStore;
import com.cryptopilot.market.repository.CoinRepository;
import com.cryptopilot.market.service.CoinDescriptionService;
import com.cryptopilot.market.service.MarketReferenceService;
import com.cryptopilot.market.service.impl.CoinDescriptionServiceImpl;
import com.cryptopilot.market.service.impl.MarketReferenceServiceImpl;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The coin names, logos and supplies and the whole market's cap from CoinPaprika, only when
 * {@code cryptopilot.market.coinpaprika.enabled} is on: off, no client, no job; the coins keep their symbols for names
 * and no logo, and the market list shows no market cap.
 *
 * <p>Rule: SRS 3.1.5.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CoinPaprikaProperties.class)
@ConditionalOnProperty(prefix = "cryptopilot.market.coinpaprika", name = "enabled", havingValue = "true")
public class CoinPaprikaConfig {

    @Bean(destroyMethod = "close")
    CoinPaprikaClient coinPaprikaClient(CoinPaprikaProperties properties, JsonMapper json) {
        return new CoinPaprikaClient(properties, json);
    }

    @Bean
    CoinDescriptionService coinDescriptionService(
            CoinRepository coins,
            CoinPaprikaClient client,
            CoinPaprikaProperties properties,
            PlatformTransactionManager transactions) {
        return new CoinDescriptionServiceImpl(coins, client, properties, transactions);
    }

    @Bean
    MarketReferenceService marketReferenceService(
            CoinPaprikaClient client,
            CoinPaprikaProperties properties,
            CoinRepository coins,
            MarketReferenceStore store,
            Clock clock) {
        return new MarketReferenceServiceImpl(client, properties, coins, store, clock);
    }

    @Bean
    MarketReferenceJob marketReferenceJob(MarketReferenceService reference, TaskScheduler scheduler, Clock clock) {
        return new MarketReferenceJob(reference, scheduler, clock);
    }

    @Bean
    CoinDescriptionJob coinDescriptionJob(CoinDescriptionService descriptions, TaskScheduler scheduler, Clock clock) {
        return new CoinDescriptionJob(descriptions, scheduler, clock);
    }
}
