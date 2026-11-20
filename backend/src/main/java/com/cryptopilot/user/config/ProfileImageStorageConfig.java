package com.cryptopilot.user.config;

import com.cryptopilot.user.client.CloudinaryProfileImageStorage;
import com.cryptopilot.user.client.ProfileImageStorage;
import com.cryptopilot.user.client.ProfileImageStorageProperties;
import com.cryptopilot.user.client.UnavailableProfileImageStorage;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses where the avatars and banners of member pages go: Cloudinary when its account is set, nowhere otherwise.
 *
 * <p>Rule: D-98, D-99.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProfileImageStorageProperties.class)
public class ProfileImageStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(ProfileImageStorageConfig.class);

    @Bean
    ProfileImageStorage profileImageStorage(ProfileImageStorageProperties properties, Clock clock) {
        if (properties.configured()) {
            log.info("Profile images are stored on Cloudinary cloud {}", properties.cloudName());
            return new CloudinaryProfileImageStorage(properties, clock);
        }
        log.warn("No Cloudinary account is set: profile image uploads are refused");
        return new UnavailableProfileImageStorage();
    }
}
