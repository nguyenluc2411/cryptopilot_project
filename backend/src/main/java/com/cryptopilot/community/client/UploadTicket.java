package com.cryptopilot.community.client;

import java.util.List;
import java.util.Objects;

/**
 * What a browser needs to upload one file straight to the media storage: the address and the signed form fields. The
 * signature covers the key the file is stored under, the formats it may have, the time it was issued and whether it
 * may replace a stored file, so the form cannot be reused for another key, cannot overwrite the file it stored, and
 * the storage refuses it once it is old.
 *
 * @param uploadUrl where the form is posted
 * @param apiKey the public key of the storage account, sent as {@code api_key}
 * @param timestamp the issue time in epoch seconds, sent as {@code timestamp}
 * @param signature the signature of the form, sent as {@code signature}
 * @param publicId the key the file is stored under, sent as {@code public_id}
 * @param allowedFormats the formats the storage accepts, sent comma-separated as {@code allowed_formats}
 * @param overwrite whether the upload may replace a stored file, sent as {@code overwrite}; always {@code false}
 */
public record UploadTicket(
        String uploadUrl,
        String apiKey,
        long timestamp,
        String signature,
        String publicId,
        List<String> allowedFormats,
        boolean overwrite) {

    public UploadTicket {
        Objects.requireNonNull(uploadUrl, "uploadUrl");
        Objects.requireNonNull(apiKey, "apiKey");
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(publicId, "publicId");
        allowedFormats = List.copyOf(allowedFormats);
    }
}
