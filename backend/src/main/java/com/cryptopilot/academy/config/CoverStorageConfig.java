package com.cryptopilot.academy.config;

import com.cryptopilot.academy.client.CloudinaryCoverStorage;
import com.cryptopilot.academy.client.CoverStorage;
import com.cryptopilot.academy.client.CoverStorageProperties;
import com.cryptopilot.academy.client.UnavailableCoverStorage;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses where the Academy's cover photos go: Cloudinary when its account is set, nowhere otherwise.
 *
 * <p>Rule: T-114.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CoverStorageProperties.class)
public class CoverStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(CoverStorageConfig.class);

    @Bean
    CoverStorage coverStorage(CoverStorageProperties properties, Clock clock) {
        if (properties.configured()) {
            log.info("Academy covers are stored on Cloudinary cloud {}", properties.cloudName());
            return new CloudinaryCoverStorage(properties, clock);
        }
        log.warn("No Cloudinary account is set: Academy cover uploads are refused, cover addresses are kept as given");
        return new UnavailableCoverStorage();
    }
}
