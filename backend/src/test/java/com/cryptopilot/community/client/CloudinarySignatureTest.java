package com.cryptopilot.community.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The Cloudinary signature against the example of its documentation, so a signed form is accepted by the real service.
 *
 * <p>Rule: D-98.
 */
class CloudinarySignatureTest {

    @Test
    void D98_theDocumentedExample_isSignedAsDocumented() {
        Map<String, String> params = Map.of(
                "timestamp", "1315060510",
                "public_id", "sample_image",
                "eager", "w_400,h_300,c_pad|w_260,h_200,c_crop");

        assertThat(CloudinarySignature.sign(params, "abcd")).isEqualTo("bfd09f95f331f558cbd1320e67aa8d488770583e");
    }

    @Test
    void D98_anEmptyValue_isLeftOutOfTheSignature() {
        Map<String, String> params = new HashMap<>();
        params.put("public_id", "sample_image");
        params.put("timestamp", "1315060510");
        params.put("eager", "w_400,h_300,c_pad|w_260,h_200,c_crop");
        params.put("folder", "");

        assertThat(CloudinarySignature.sign(params, "abcd")).isEqualTo("bfd09f95f331f558cbd1320e67aa8d488770583e");
    }
}
