package com.cryptopilot.academy.dto.response;

import java.util.List;

/**
 * The signed form of one cover upload: the browser posts the photo to {@code uploadUrl} as {@code file} with these
 * fields, then saves the lesson with the {@code secure_url} Cloudinary answers.
 *
 * @param uploadUrl where the form is posted
 * @param apiKey sent as {@code api_key}
 * @param timestamp sent as {@code timestamp}
 * @param signature sent as {@code signature}
 * @param publicId sent as {@code public_id}
 * @param allowedFormats sent comma-separated as {@code allowed_formats}
 */
public record CoverUploadResponse(
        String uploadUrl,
        String apiKey,
        long timestamp,
        String signature,
        String publicId,
        List<String> allowedFormats) {

    public CoverUploadResponse {
        allowedFormats = List.copyOf(allowedFormats);
    }
}
