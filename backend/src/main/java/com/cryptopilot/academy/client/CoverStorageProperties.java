package com.cryptopilot.academy.client;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/**
 * The Cloudinary account the Academy's cover photos are kept in. The same account as the community's media, read from
 * the same environment variables, with a folder of its own.
 *
 * <p>Rule: T-114.
 *
 * @param cloudName the account's cloud name, or empty when none is set
 * @param apiKey the account's public key, or empty
 * @param apiSecret the key that signs uploads, or empty; never logged
 * @param apiBaseUrl Cloudinary's API address
 * @param deliveryBaseUrl Cloudinary's delivery address
 * @param folder where cover photos are stored, e.g. {@code cryptopilot/academy}
 * @param transformation how a cover is delivered, e.g. {@code c_fill,g_auto,w_1200,h_675,q_auto,f_auto}
 */
@Validated
@ConfigurationProperties("cryptopilot.academy.cover")
public record CoverStorageProperties(
        String cloudName,
        String apiKey,
        String apiSecret,
        @NotNull URI apiBaseUrl,
        @NotNull URI deliveryBaseUrl,
        @NotBlank String folder,
        @NotBlank String transformation) {

    public boolean configured() {
        return StringUtils.hasText(cloudName) && StringUtils.hasText(apiKey) && StringUtils.hasText(apiSecret);
    }

    @Override
    public String toString() {
        // The secret stays out of every log line and error message that prints the properties.
        return "CoverStorageProperties[cloudName=" + cloudName + ", folder=" + folder + "]";
    }
}
