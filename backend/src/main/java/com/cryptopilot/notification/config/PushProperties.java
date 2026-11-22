package com.cryptopilot.notification.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Push notifications through Firebase Cloud Messaging, {@code cryptopilot.push} (T-058, NSF-15).
 *
 * @param credentialsFile the service account key file of the Firebase project ({@code FCM_SERVICE_ACCOUNT_PATH}); blank
 *     for none, and then pushes are logged instead of sent
 * @param projectId the Firebase project, or blank for the key file's own
 * @param apiUrl the base of the FCM HTTP v1 API
 * @param attempts how many times one message is sent before it is given up: NSF-15 retries a failed delivery 3 times
 * @param retryDelay the wait before the first retry; each retry waits twice the one before it
 * @param timeout how long one call to the service may take
 * @param poolSize the threads of the push pool, the only ones that call the service or wait between its retries
 * @param queueCapacity the dispatches that may wait for the push pool; past it a push is left to a resubmission
 */
@Validated
@ConfigurationProperties("cryptopilot.push")
public record PushProperties(
        @DefaultValue("") String credentialsFile,
        @DefaultValue("") String projectId,

        @NotBlank @DefaultValue("https://fcm.googleapis.com")
        String apiUrl,

        @Min(1) @Max(10) @DefaultValue("4") int attempts,
        @NotNull @DefaultValue("500ms") Duration retryDelay,
        @NotNull @DefaultValue("10s") Duration timeout,
        @Min(1) @Max(32) @DefaultValue("2") int poolSize,
        @Min(1) @DefaultValue("500") int queueCapacity) {}
