package com.cryptopilot.user.client;

import java.util.List;
import java.util.Objects;

/**
 * The signed form of one profile image upload, in the shape Cloudinary's upload API takes: the browser posts the file to
 * {@code uploadUrl} with these fields, then names {@code publicId} in the profile.
 *
 * <p>Rule: D-98, D-99.
 *
 * @param uploadUrl where the form is posted
 * @param apiKey the account's public key, sent as {@code api_key}
 * @param timestamp the signing instant in epoch seconds, sent as {@code timestamp}
 * @param signature the signature of the fields below, sent as {@code signature}
 * @param publicId the key the image is stored under, sent as {@code public_id}
 * @param allowedFormats the image formats accepted, sent comma-separated as {@code allowed_formats}
 */
public record ProfileImageUploadTicket(
        String uploadUrl,
        String apiKey,
        long timestamp,
        String signature,
        String publicId,
        List<String> allowedFormats) {

    public ProfileImageUploadTicket {
        Objects.requireNonNull(uploadUrl, "uploadUrl");
        Objects.requireNonNull(apiKey, "apiKey");
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(publicId, "publicId");
        allowedFormats = List.copyOf(allowedFormats);
    }
}
