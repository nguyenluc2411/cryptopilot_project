package com.cryptopilot.community.client;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/**
 * The Cloudinary account the post media are stored in, from {@code CLOUDINARY_CLOUD_NAME}, {@code CLOUDINARY_API_KEY}
 * and {@code CLOUDINARY_API_SECRET}. All three empty means no account is configured: outside the prod profile media
 * are then refused and text posts still work; the prod profile refuses to start.
 *
 * <p>Rule: BR-44; D-98; TECHNICAL_DESIGN 12 (configuration from the environment).
 *
 * @param cloudName the account's cloud name, part of every address
 * @param apiKey the public key, sent with every signed form
 * @param apiSecret the secret that signs forms and authenticates the Admin API; never logged or returned
 * @param apiBaseUrl the API host, {@code https://api.cloudinary.com}; a test points it at a local stand-in
 * @param folder the folder every post file is stored under, followed by the uploader's account key
 * @param connectTimeout how long to wait for a connection
 * @param readTimeout how long to wait for an answer
 */
@Validated
@ConfigurationProperties("cryptopilot.media.cloudinary")
public record CloudinaryProperties(
        String cloudName,
        String apiKey,
        String apiSecret,
        @NotNull URI apiBaseUrl,
        @NotBlank String folder,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout) {

    /** Whether an account is configured: a cloud name, a key and a secret. */
    public boolean configured() {
        return StringUtils.hasText(cloudName) && StringUtils.hasText(apiKey) && StringUtils.hasText(apiSecret);
    }

    @Override
    public String toString() {
        // The secret stays out of every log line and error message that prints the properties.
        return "CloudinaryProperties[cloudName=" + cloudName + ", apiBaseUrl=" + apiBaseUrl + ", folder=" + folder
                + "]";
    }
}
