package com.cryptopilot.community.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.community.model.enums.MediaStatus;
import com.cryptopilot.community.model.enums.PostMediaType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

/**
 * An image or video attached to a post, as the media storage described it when it was attached (BR-44). The file
 * itself is in the storage under {@link #getStoragePublicId()}; a key is attached to one post only
 * ({@code uq_post_media_storage_public_id}).
 *
 * <p>Rule: BR-44; SRS 3.7.2.
 */
@Getter
@Entity
@Table(name = "post_media")
@AttributeOverride(name = "id", column = @Column(name = "media_id", nullable = false, updatable = false))
public class PostMedia extends BaseEntity {

    @Column(name = "post_id", nullable = false, updatable = false)
    private UUID postId;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false, length = 32, updatable = false)
    private PostMediaType mediaType;

    @Column(name = "storage_public_id", nullable = false, updatable = false)
    private String storagePublicId;

    @Column(name = "media_url", nullable = false, length = 1000)
    private String mediaUrl;

    @Column(name = "thumbnail_url", length = 1000)
    private String thumbnailUrl;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 32)
    private MediaStatus processingStatus;

    /** The position among the files of the post, from 0. */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** For JPA only. */
    protected PostMedia() {}

    private PostMedia(
            UUID postId,
            PostMediaType mediaType,
            String storagePublicId,
            String mediaUrl,
            String thumbnailUrl,
            long fileSizeBytes,
            Integer durationSeconds,
            Integer width,
            Integer height,
            int sortOrder) {
        this.postId = Objects.requireNonNull(postId, "postId must not be null");
        this.mediaType = Objects.requireNonNull(mediaType, "mediaType must not be null");
        this.storagePublicId = Objects.requireNonNull(storagePublicId, "storagePublicId must not be null");
        this.mediaUrl = Objects.requireNonNull(mediaUrl, "mediaUrl must not be null");
        this.thumbnailUrl = thumbnailUrl;
        this.fileSizeBytes = fileSizeBytes;
        this.durationSeconds = durationSeconds;
        this.width = width;
        this.height = height;
        this.processingStatus = MediaStatus.READY;
        moveTo(sortOrder);
    }

    /** A checked file attached to a post at a position. */
    public static PostMedia attach(
            UUID postId,
            PostMediaType mediaType,
            String storagePublicId,
            String mediaUrl,
            String thumbnailUrl,
            long fileSizeBytes,
            Integer durationSeconds,
            Integer width,
            Integer height,
            int sortOrder) {
        return new PostMedia(
                postId,
                mediaType,
                storagePublicId,
                mediaUrl,
                thumbnailUrl,
                fileSizeBytes,
                durationSeconds,
                width,
                height,
                sortOrder);
    }

    /** Moves the file to another position among the files of its post. */
    public void moveTo(int sortOrder) {
        if (sortOrder < 0) {
            throw new IllegalArgumentException("sortOrder must not be negative, was " + sortOrder);
        }
        this.sortOrder = sortOrder;
    }
}
