package com.cryptopilot.journal.config;

import com.cryptopilot.journal.client.ChartImageStorage;
import com.cryptopilot.journal.client.ChartImageStorageProperties;
import com.cryptopilot.journal.client.CloudinaryChartImageStorage;
import com.cryptopilot.journal.client.UnavailableChartImageStorage;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses where the chart images of reviews go: Cloudinary when its account is set, nowhere otherwise.
 *
 * <p>Rule: BR-40; D-98.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChartImageStorageProperties.class)
public class ChartImageStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(ChartImageStorageConfig.class);

    @Bean
    ChartImageStorage chartImageStorage(ChartImageStorageProperties properties, Clock clock) {
        if (properties.configured()) {
            log.info("Journal chart images are stored on Cloudinary cloud {}", properties.cloudName());
            return new CloudinaryChartImageStorage(properties, clock);
        }
        log.warn("No Cloudinary account is set: journal chart image uploads are refused");
        return new UnavailableChartImageStorage();
    }
}
