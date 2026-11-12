package com.cryptopilot.journal.dto.response;

import java.util.List;

/**
 * A signed form to upload one chart image of a review straight to the media storage: the browser posts the file to
 * {@code uploadUrl} with {@code api_key}, {@code timestamp}, {@code signature}, {@code public_id} and
 * {@code allowed_formats}, then saves the review with {@code publicId}.
 *
 * <p>Rule: BR-40; SRS 3.6.1; D-98.
 *
 * @param uploadUrl where the form is posted
 * @param apiKey the storage account's public key
 * @param timestamp the signing instant in epoch seconds
 * @param signature the signature of the form
 * @param publicId the key the image is stored under, named in the review afterwards
 * @param allowedFormats the image formats accepted
 * @param maxBytes the largest file the review accepts
 */
public record ChartImageUploadResponse(
        String uploadUrl,
        String apiKey,
        long timestamp,
        String signature,
        String publicId,
        List<String> allowedFormats,
        long maxBytes) {}
