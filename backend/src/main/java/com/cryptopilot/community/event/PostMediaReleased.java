package com.cryptopilot.community.event;

import com.cryptopilot.community.model.enums.PostMediaType;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Files a post no longer carries, to be deleted from the media storage: the files of a deleted post or the files an
 * edit removed. Published in the transaction that released them, so the delete
 * runs only after that change committed, and is retried by the outbox processor when the storage fails (SRS 3.7.2,
 * "Media Deletion Request").
 *
 * <p>Rule: BR-47; SRS 3.7.2; NSF-15 (delivery through the event publication registry); D-98.
 *
 * @param postId the post they belonged to
 * @param files the files
 */
public record PostMediaReleased(UUID postId, List<File> files) {

    public PostMediaReleased {
        files = List.copyOf(files);
    }

    /**
     * One released file.
     *
     * @param type image or video, which decides the storage's resource type
     * @param publicId its key in the storage
     */
    public record File(PostMediaType type, String publicId) {

        public File {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(publicId, "publicId");
        }
    }
}
