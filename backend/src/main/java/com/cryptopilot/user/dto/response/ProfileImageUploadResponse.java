package com.cryptopilot.user.dto.response;

import java.util.List;

/**
 * A signed form to upload one avatar or banner straight to the media storage (D-99): the browser posts the file to
 * {@code uploadUrl} with {@code api_key}, {@code timestamp}, {@code signature}, {@code public_id} and
 * {@code allowed_formats}, then saves the member page with {@code publicId}.
 *
 * <p>Rule: D-98, D-99.
 *
 * @param uploadUrl where the form is posted
 * @param apiKey the storage account's public key
 * @param timestamp the signing instant in epoch seconds
 * @param signature the signature of the form
 * @param publicId the key the image is stored under, named in the profile afterwards
 * @param allowedFormats the image formats accepted
 * @param maxBytes the largest file the profile accepts
 */
public record ProfileImageUploadResponse(
        String uploadUrl,
        String apiKey,
        long timestamp,
        String signature,
        String publicId,
        List<String> allowedFormats,
        long maxBytes) {}
