package com.cryptopilot.billing.config;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The billing settings of a deployment.
 *
 * @param returnUrl the page of the web application a gateway sends the browser back to after a payment
 *     ({@code /subscription/payment-result}, SCR-31); the order code is added as {@code orderCode} (D-147)
 */
@Validated
@ConfigurationProperties("cryptopilot.billing")
public record BillingProperties(
        @NotNull @DefaultValue("http://localhost:5173/subscription/payment-result")
        URI returnUrl) {}
