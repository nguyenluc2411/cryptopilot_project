package com.cryptopilot.billing.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The MoMo Payment Gateway credentials of a deployment ({@code MOMO_*}). MoMo is offered in its test environment only
 * (D-147): taking real money through MoMo needs a registered business.
 *
 * @param partnerCode the partner code; empty leaves MoMo unavailable
 * @param accessKey the access key
 * @param secretKey the secret key: signs every request and checks every IPN
 * @param endpoint the API base of the MoMo test environment; empty leaves MoMo unavailable (named in the dev profile)
 * @param ipnUrl the public address of {@code /api/v1/billing/ipn/momo}; MoMo is told it with each order. Empty leaves
 *     MoMo unavailable, since a payment would never be confirmed
 */
@Validated
@ConfigurationProperties("cryptopilot.billing.momo")
public record MoMoProperties(
        @DefaultValue("") String partnerCode,
        @DefaultValue("") String accessKey,
        @DefaultValue("") String secretKey,
        @DefaultValue("") String endpoint,

        @DefaultValue("") String ipnUrl) {

    /** Whether every credential, the API base and the IPN address are present. */
    public boolean configured() {
        return !partnerCode.isBlank()
                && !accessKey.isBlank()
                && !secretKey.isBlank()
                && !endpoint.isBlank()
                && !ipnUrl.isBlank();
    }
}
