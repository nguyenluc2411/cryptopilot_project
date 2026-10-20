package com.cryptopilot.community.dto.response;

import com.cryptopilot.community.model.enums.PostMediaType;

/**
 * An image or video of a post, as SCR-24 previews it and SCR-25 shows it.
 *
 * @param publicId its key, which an edit names to keep it
 * @param mediaType IMAGE or VIDEO
 * @param url where it is served from
 * @param thumbnailUrl a still of a video; {@code null} for an image
 * @param width its width in pixels, or {@code null}
 * @param height its height in pixels, or {@code null}
 * @param durationSeconds a video's length; {@code null} for an image
 */
public record MediaResponse(
        String publicId,
        PostMediaType mediaType,
        String url,
        String thumbnailUrl,
        Integer width,
        Integer height,
        Integer durationSeconds) {}
