package com.cryptopilot.billing.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The base configuration names no gateway address: an empty address leaves the gateway unavailable, exactly as an
 * empty key does (D-147).
 */
class GatewayPropertiesTest {

    @Test
    void D147_sePayWithKeysButNoAddress_isUnavailable() {
        assertThat(new SePayProperties("M", "spsk_test_x", "ipn", "", "").configured())
                .isFalse();
        assertThat(new SePayProperties("M", "spsk_test_x", "ipn", "https://pay.example.test", "").configured())
                .isFalse();
        assertThat(new SePayProperties("M", "spsk_test_x", "ipn", "", "https://api.example.test").configured())
                .isFalse();
        assertThat(new SePayProperties(
                                "M", "spsk_test_x", "ipn", "https://pay.example.test", "https://api.example.test")
                        .configured())
                .isTrue();
    }

    @Test
    void D147_moMoWithKeysButNoEndpoint_isUnavailable() {
        String ipn = "https://api.example.test/api/v1/billing/ipn/momo";

        assertThat(new MoMoProperties("MOMO", "ACCESS", "secret", "", ipn).configured())
                .isFalse();
        assertThat(new MoMoProperties("MOMO", "ACCESS", "secret", " ", ipn).configured())
                .isFalse();
        assertThat(new MoMoProperties("MOMO", "ACCESS", "secret", "https://momo.example.test", ipn).configured())
                .isTrue();
    }
}
