package com.cryptopilot.billing.calculator;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The signature of a SePay checkout form, as the SePay Payment Gateway documentation defines it ("Create payment
 * order API"): only the fields of {@link #SIGNED_FIELDS}, in that order and no other, each written {@code name=value}
 * with its raw value, joined by commas; a field that is absent, empty or {@code "0"} is left out. The string is signed
 * with HMAC-SHA256 under the merchant secret key and the signature is the Base64 of the raw digest.
 *
 * <p>The order is the documentation's and must not change: SePay recomputes the signature over the same sequence.
 *
 * <p>Rule: D-147. Reference: SePay, "Cổng thanh toán - Tạo form thanh toán", developer.sepay.vn (2025).
 */
public final class SePaySignature {

    /** The signed fields, in the order SePay signs them. */
    public static final List<String> SIGNED_FIELDS = List.of(
            "order_amount",
            "merchant",
            "currency",
            "operation",
            "order_description",
            "order_invoice_number",
            "customer_id",
            "payment_method",
            "success_url",
            "error_url",
            "cancel_url");

    private static final String ALGORITHM = "HmacSHA256";

    private SePaySignature() {}

    /** The string SePay signs for these form fields. */
    public static String signedString(Map<String, String> fields) {
        Objects.requireNonNull(fields, "fields must not be null");
        List<String> parts = new ArrayList<>();
        for (String name : SIGNED_FIELDS) {
            String value = fields.get(name);
            if (value == null || value.isEmpty() || "0".equals(value)) {
                continue;
            }
            parts.add(name + "=" + value);
        }
        return String.join(",", parts);
    }

    /** The Base64 HMAC-SHA256 signature of the form fields under the merchant secret key. */
    public static String sign(Map<String, String> fields, String secretKey) {
        Objects.requireNonNull(secretKey, "secretKey must not be null");
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] digest = mac.doFinal(signedString(fields).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
