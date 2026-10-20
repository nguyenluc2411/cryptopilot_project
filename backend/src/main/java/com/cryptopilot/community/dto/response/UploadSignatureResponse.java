package com.cryptopilot.community.dto.response;

import com.cryptopilot.community.model.enums.PostMediaType;
import java.util.List;

/**
 * A signed form for one file: the browser posts the file to {@code uploadUrl} as {@code multipart/form-data} with the
 * fields {@code file}, {@code api_key}, {@code timestamp}, {@code signature}, {@code public_id} and
 * {@code allowed_formats} (comma-separated), then names {@code publicId} in the post. The limits are for the form's
 * own check before the upload; the backend checks the stored file again.
 *
 * <p>Rule: BR-44; D-98.
 *
 * @param uploadUrl where the file is posted
 * @param apiKey the {@code api_key} field
 * @param timestamp the {@code timestamp} field; the form is refused by the storage an hour later
 * @param signature the {@code signature} field
 * @param publicId the {@code public_id} field, and the key to name in the post
 * @param allowedFormats the {@code allowed_formats} field, e.g. {@code jpg, png, webp}
 * @param mediaType the kind of file the form is for
 * @param maxBytes the largest file accepted
 * @param maxDurationSeconds the longest video accepted; {@code null} for an image
 */
public record UploadSignatureResponse(
        String uploadUrl,
        String apiKey,
        long timestamp,
        String signature,
        String publicId,
        List<String> allowedFormats,
        PostMediaType mediaType,
        long maxBytes,
        Integer maxDurationSeconds) {}
