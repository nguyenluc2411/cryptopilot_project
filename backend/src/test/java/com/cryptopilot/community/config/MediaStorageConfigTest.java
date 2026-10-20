package com.cryptopilot.community.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.community.client.CloudinaryClient;
import com.cryptopilot.community.client.CloudinaryProperties;
import com.cryptopilot.community.client.UnavailableMediaStorage;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The choice of the media storage at start-up: Cloudinary with an account, a storage that refuses media without one,
 * and no start of the prod profile without one.
 *
 * <p>Rule: BR-44; D-98.
 */
class MediaStorageConfigTest {

    private final MediaStorageConfig config = new MediaStorageConfig();

    @Test
    void D98_anAccount_selectsCloudinary() {
        assertThat(config.mediaStorage(properties("cloud", "key", "secret"), Clock.systemUTC(), new MockEnvironment()))
                .isInstanceOf(CloudinaryClient.class);
    }

    @Test
    void D98_noAccountOutsideProd_refusesMediaButStarts() {
        assertThat(config.mediaStorage(properties("", "", ""), Clock.systemUTC(), new MockEnvironment()))
                .isInstanceOf(UnavailableMediaStorage.class);
    }

    @Test
    void D98_noAccountInProd_refusesToStart() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        assertThatThrownBy(() -> config.mediaStorage(properties("cloud", "", ""), Clock.systemUTC(), prod))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLOUDINARY_API_SECRET");
    }

    private static CloudinaryProperties properties(String cloud, String key, String secret) {
        return new CloudinaryProperties(
                cloud,
                key,
                secret,
                URI.create("https://api.cloudinary.com"),
                "cryptopilot/posts",
                Duration.ofSeconds(5),
                Duration.ofSeconds(10));
    }
}
