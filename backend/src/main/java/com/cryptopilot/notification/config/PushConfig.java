package com.cryptopilot.notification.config;

import com.cryptopilot.notification.client.FcmCredentials;
import com.cryptopilot.notification.client.FcmPushSender;
import com.cryptopilot.notification.client.LoggingPushSender;
import com.cryptopilot.notification.client.PushSender;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Chooses the push adapter at start-up (T-058): Firebase Cloud Messaging when a service account key file is configured
 * ({@code FCM_SERVICE_ACCOUNT_PATH}), a log line otherwise. Unlike mail, a missing key does not stop the prod profile: the
 * web application works without push, and NSF-15 still shows every notification in the app.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PushProperties.class)
public class PushConfig {

    private static final Logger log = LoggerFactory.getLogger(PushConfig.class);

    @Bean
    PushSender pushSender(PushProperties properties, Clock clock) {
        if (properties.credentialsFile().isBlank()) {
            log.warn("FCM_SERVICE_ACCOUNT_PATH is not set: push notifications are logged, not sent");
            return new LoggingPushSender();
        }
        FcmCredentials credentials =
                FcmCredentials.fromFile(Path.of(properties.credentialsFile()), properties.projectId());
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.timeout()).build());
        requests.setReadTimeout(properties.timeout());
        log.info("Push notifications go out through FCM project {}", credentials.projectId());
        return new FcmPushSender(
                credentials, RestClient.builder().requestFactory(requests).build(), properties.apiUrl(), clock);
    }
}
