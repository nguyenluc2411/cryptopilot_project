package com.cryptopilot.billing.client;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The MoMo Payment Gateway credentials of a deployment ({@code MOMO_*}). MoMo is offered in its test environment only
 * (D-146): taking real money through MoMo needs a registered business.
 *
 * @param partnerCode the partner code; empty leaves MoMo unavailable
 * @param accessKey the access key
 * @param secretKey the secret key: signs every request and checks every IPN
 * @param endpoint the API base ({@code https://test-payment.momo.vn})
 * @param ipnUrl the public address of {@code /api/v1/billing/ipn/momo}; MoMo is told it with each order. Empty leaves
 *     MoMo unavailable, since a payment would never be confirmed
 */
@Validated
@ConfigurationProperties("cryptopilot.billing.momo")
public record MoMoProperties(
        @DefaultValue("") String partnerCode,
        @DefaultValue("") String accessKey,
        @DefaultValue("") String secretKey,

        @NotNull @DefaultValue("https://test-payment.momo.vn")
        URI endpoint,

        @DefaultValue("") String ipnUrl) {

    /** Whether every credential and the IPN address are present. */
    public boolean configured() {
        return !partnerCode.isBlank() && !accessKey.isBlank() && !secretKey.isBlank() && !ipnUrl.isBlank();
    }
}
