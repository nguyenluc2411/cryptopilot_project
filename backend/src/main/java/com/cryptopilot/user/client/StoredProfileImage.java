package com.cryptopilot.user.client;

import java.util.Objects;

/**
 * A profile image as the storage describes it after the browser uploaded it; every value comes from the storage, never
 * from the client.
 *
 * <p>Rule: D-98, D-99.
 *
 * @param publicId the key the image is stored under
 * @param format the format the storage detected, lower case, e.g. {@code png}
 * @param bytes the size of the file
 * @param url the HTTPS address the image is served from
 */
public record StoredProfileImage(String publicId, String format, long bytes, String url) {

    public StoredProfileImage {
        Objects.requireNonNull(publicId, "publicId");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(url, "url");
    }
}
