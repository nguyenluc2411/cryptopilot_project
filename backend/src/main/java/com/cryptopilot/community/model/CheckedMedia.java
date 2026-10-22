package com.cryptopilot.community.model;

import com.cryptopilot.community.client.StoredMedia;
import com.cryptopilot.community.model.enums.PostMediaType;
import java.util.Objects;

/**
 * One file a post is to carry after the media rules were checked: either a new upload, with what the storage reported
 * about it, or a file the post already carries and keeps as it is.
 *
 * @param publicId its key in the storage
 * @param type image or video
 * @param upload what the storage reported about a new file; {@code null} for a file the post keeps
 */
public record CheckedMedia(String publicId, PostMediaType type, StoredMedia upload) {

    public CheckedMedia {
        Objects.requireNonNull(publicId, "publicId");
        Objects.requireNonNull(type, "type");
    }

    /** Whether this is a file the post already carries. */
    public boolean kept() {
        return upload == null;
    }
}
