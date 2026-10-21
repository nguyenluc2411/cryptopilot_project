package com.cryptopilot.community.config;

import com.cryptopilot.community.client.CloudinaryClient;
import com.cryptopilot.community.client.CloudinaryProperties;
import com.cryptopilot.community.client.MediaStorage;
import com.cryptopilot.community.client.UnavailableMediaStorage;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Chooses the media storage at start-up (T-061): Cloudinary when an account is configured, otherwise a storage that
 * refuses media. The prod profile has no fallback and refuses to start without an account, because a post form that
 * offers images and then refuses every one is a defect a deployment should not hide.
 *
 * <p>Rule: BR-44; D-98.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({CloudinaryProperties.class, MediaSweepProperties.class})
public class MediaStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(MediaStorageConfig.class);

    @Bean
    MediaStorage mediaStorage(CloudinaryProperties properties, Clock clock, Environment environment) {
        if (properties.configured()) {
            log.info("Post media are stored on Cloudinary cloud {}", properties.cloudName());
            return new CloudinaryClient(properties, clock);
        }
        if (environment.matchesProfiles("prod")) {
            throw new IllegalStateException("No Cloudinary account is set: the prod profile stores post media there and"
                    + " has no fallback. Set CLOUDINARY_CLOUD_NAME, CLOUDINARY_API_KEY and CLOUDINARY_API_SECRET.");
        }
        log.warn("No Cloudinary account is set: posts with images or videos are refused, text posts work");
        return new UnavailableMediaStorage();
    }
}
