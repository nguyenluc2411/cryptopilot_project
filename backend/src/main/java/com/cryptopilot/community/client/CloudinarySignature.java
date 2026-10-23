package com.cryptopilot.community.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The signature of a Cloudinary request: the parameters sorted by name and joined as {@code name=value} pairs with
 * {@code &}, the API secret appended, hashed with SHA-1 and written as lower-case hex. {@code file}, {@code api_key},
 * {@code resource_type} and {@code cloud_name} are never signed, and an empty value is left out.
 *
 * <p>Rule: D-98.
 *
 * <p>Reference: Cloudinary. <i>Authentication signatures</i>, "Manual signature generation" (Cloudinary
 * documentation, 2025).
 */
public final class CloudinarySignature {

    private CloudinarySignature() {}

    /** The signature of these parameters under this secret. */
    public static String sign(Map<String, String> params, String apiSecret) {
        String toSign = new TreeMap<>(params)
                .entrySet().stream()
                        .filter(param ->
                                param.getValue() != null && !param.getValue().isEmpty())
                        .map(param -> param.getKey() + "=" + param.getValue())
                        .collect(Collectors.joining("&"));
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-1").digest((toSign + apiSecret).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException unavailable) {
            // Every Java platform must provide SHA-1 (MessageDigest specification).
            throw new IllegalStateException("SHA-1 is not available", unavailable);
        }
    }
}
