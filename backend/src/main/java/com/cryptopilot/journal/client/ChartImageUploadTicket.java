package com.cryptopilot.journal.client;

import java.util.List;
import java.util.Objects;

/**
 * The signed form of one chart image upload, in the shape Cloudinary's upload API takes: the browser posts the file to
 * {@code uploadUrl} with these fields, then names {@code publicId} in the review.
 *
 * <p>Rule: BR-40; D-98.
 *
 * @param uploadUrl where the form is posted
 * @param apiKey the account's public key, sent as {@code api_key}
 * @param timestamp the signing instant in epoch seconds, sent as {@code timestamp}
 * @param signature the signature of the fields below, sent as {@code signature}
 * @param publicId the key the image is stored under, sent as {@code public_id}
 * @param allowedFormats the image formats accepted, sent comma-separated as {@code allowed_formats}
 */
public record ChartImageUploadTicket(
        String uploadUrl,
        String apiKey,
        long timestamp,
        String signature,
        String publicId,
        List<String> allowedFormats) {

    public ChartImageUploadTicket {
        Objects.requireNonNull(uploadUrl, "uploadUrl");
        Objects.requireNonNull(apiKey, "apiKey");
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(publicId, "publicId");
        allowedFormats = List.copyOf(allowedFormats);
    }
}
