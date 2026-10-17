package com.cryptopilot.notification.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Who mail comes from and where its links lead (T-057). The SMTP server itself is {@code spring.mail.*}.
 *
 * @param from the sender address ({@code SMTP_FROM})
 * @param publicBaseUrl the web application the links open ({@code PUBLIC_BASE_URL}), e.g. {@code https://app.example.com}
 */
@Validated
@ConfigurationProperties(prefix = "cryptopilot.notification.mail")
public record MailDeliveryProperties(
        @NotBlank String from, @NotNull URI publicBaseUrl) {}
