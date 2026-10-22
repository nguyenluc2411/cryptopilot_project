package com.cryptopilot.community.client;

import com.cryptopilot.community.model.enums.PostMediaType;
import java.util.Objects;

/**
 * A file as the media storage describes it, read back after the browser uploaded it: what is checked against BR-44
 * and stored with the post. Every value comes from the storage, never from the client.
 *
 * @param publicId the key the file is stored under
 * @param type image or video
 * @param format the file format the storage detected, lower case, e.g. {@code jpg} or {@code mp4}
 * @param bytes the size of the file
 * @param width its width in pixels, or {@code null} when unknown
 * @param height its height in pixels, or {@code null} when unknown
 * @param durationSeconds a video's length rounded up to whole seconds; {@code null} for an image
 * @param url the HTTPS address the file is served from
 * @param thumbnailUrl a still image of a video; {@code null} for an image
 */
public record StoredMedia(
        String publicId,
        PostMediaType type,
        String format,
        long bytes,
        Integer width,
        Integer height,
        Integer durationSeconds,
        String url,
        String thumbnailUrl) {

    public StoredMedia {
        Objects.requireNonNull(publicId, "publicId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(url, "url");
    }
}
