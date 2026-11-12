package com.cryptopilot.journal.client;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/**
 * The Cloudinary account chart images are kept in: the community's account, read from the same environment variables,
 * with a folder of its own, so the sweep of unattached post files never reaches them.
 *
 * <p>Rule: BR-40; D-98.
 *
 * @param cloudName the account's cloud name, or empty when none is set
 * @param apiKey the account's public key, or empty
 * @param apiSecret the key that signs uploads and authenticates the Admin API, or empty; never logged
 * @param apiBaseUrl Cloudinary's API address
 * @param folder where chart images are stored, followed by the owner's account key, e.g. {@code cryptopilot/journal}
 * @param connectTimeout how long to wait for a connection
 * @param readTimeout how long to wait for an answer
 */
@Validated
@ConfigurationProperties("cryptopilot.journal.chart-image")
public record ChartImageStorageProperties(
        String cloudName,
        String apiKey,
        String apiSecret,
        @NotNull URI apiBaseUrl,
        @NotBlank String folder,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout) {

    public boolean configured() {
        return StringUtils.hasText(cloudName) && StringUtils.hasText(apiKey) && StringUtils.hasText(apiSecret);
    }

    @Override
    public String toString() {
        // The secret stays out of every log line and error message that prints the properties.
        return "ChartImageStorageProperties[cloudName=" + cloudName + ", folder=" + folder + "]";
    }
}
