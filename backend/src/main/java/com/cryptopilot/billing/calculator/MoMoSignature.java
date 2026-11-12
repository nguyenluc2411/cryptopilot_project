package com.cryptopilot.billing.calculator;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The MoMo v2 signature (D-147): {@code name=value} pairs of the listed fields, in the listed order, joined by
 * {@code &}, signed with HMAC-SHA256 under the secret key and written as lowercase hex. A missing value signs as empty.
 *
 * <p>Rule: BR-53; D-147. Reference: MoMo sample code, github.com/momo-wallet/payment (nodejs/MoMo.js, php/PayMoMo).
 */
public final class MoMoSignature {

    /** The fields of a create request (captureWallet), in the order MoMo signs them. */
    public static final List<String> CREATE = List.of(
            "accessKey",
            "amount",
            "extraData",
            "ipnUrl",
            "orderId",
            "orderInfo",
            "partnerCode",
            "redirectUrl",
            "requestId",
            "requestType");

    /** The fields of a query request, in the order MoMo signs them. */
    public static final List<String> QUERY = List.of("accessKey", "orderId", "partnerCode", "requestId");

    /** The fields of an IPN, in the order MoMo signs them. */
    public static final List<String> IPN = List.of(
            "accessKey",
            "amount",
            "extraData",
            "message",
            "orderId",
            "orderInfo",
            "orderType",
            "partnerCode",
            "payType",
            "requestId",
            "responseTime",
            "resultCode",
            "transId");

    private static final String ALGORITHM = "HmacSHA256";

    private MoMoSignature() {}

    /** The string MoMo signs for these fields. */
    public static String signedString(List<String> order, Map<String, String> fields) {
        Objects.requireNonNull(order, "order must not be null");
        Objects.requireNonNull(fields, "fields must not be null");
        List<String> parts = new ArrayList<>();
        for (String name : order) {
            String value = fields.get(name);
            parts.add(name + "=" + (value == null ? "" : value));
        }
        return String.join("&", parts);
    }

    /** The hex HMAC-SHA256 signature of the fields under the secret key. */
    public static String sign(List<String> order, Map<String, String> fields, String secretKey) {
        Objects.requireNonNull(secretKey, "secretKey must not be null");
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of()
                    .formatHex(mac.doFinal(signedString(order, fields).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
