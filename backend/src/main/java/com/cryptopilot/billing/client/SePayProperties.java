package com.cryptopilot.billing.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The SePay Payment Gateway credentials and addresses of a deployment ({@code SEPAY_*}). None has a default: an empty
 * key or address leaves SePay unavailable. The dev profile names the sandbox; going live is the production merchant
 * id, keys and addresses, nothing else.
 *
 * @param merchantId the MERCHANT ID; empty leaves SePay unavailable
 * @param secretKey the merchant SECRET KEY ({@code spsk_test_...} or {@code spsk_live_...}): signs the checkout form
 * @param ipnSecretKey the IPN secret key chosen under Payment Gateway, Configuration, IPN (auth type SECRET_KEY): SePay
 *     sends it in {@code X-Secret-Key}; a different value from {@code secretKey}
 * @param checkoutUrl the checkout form endpoint ({@code https://pay.sepay.vn/v1/checkout/init} in production)
 * @param apiUrl the base of the order query API ({@code https://pgapi.sepay.vn} in production), Basic authentication
 *     with the merchant id and secret key
 */
@Validated
@ConfigurationProperties("cryptopilot.billing.sepay")
public record SePayProperties(
        @DefaultValue("") String merchantId,
        @DefaultValue("") String secretKey,
        @DefaultValue("") String ipnSecretKey,
        @DefaultValue("") String checkoutUrl,
        @DefaultValue("") String apiUrl) {

    /** Whether every credential and both addresses are present. */
    public boolean configured() {
        return !merchantId.isBlank()
                && !secretKey.isBlank()
                && !ipnSecretKey.isBlank()
                && !checkoutUrl.isBlank()
                && !apiUrl.isBlank();
    }
}
