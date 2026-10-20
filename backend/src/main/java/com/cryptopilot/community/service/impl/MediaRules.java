package com.cryptopilot.community.service.impl;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.model.enums.Feature;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.util.UuidV7;
import com.cryptopilot.community.client.CloudinaryProperties;
import com.cryptopilot.community.client.MediaStorage;
import com.cryptopilot.community.client.MediaStorageException;
import com.cryptopilot.community.client.StoredMedia;
import com.cryptopilot.community.dto.request.MediaRequest;
import com.cryptopilot.community.entity.PostMedia;
import com.cryptopilot.community.model.CheckedMedia;
import com.cryptopilot.community.model.enums.PostMediaType;
import com.cryptopilot.community.repository.PostMediaRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The media rules of a post (BR-43, BR-44), checked against what the media storage actually holds rather than what the
 * client says: up to {@code MAX_IMAGES_PER_POST} images in JPG, PNG or WEBP of at most {@code MAX_IMAGE_SIZE_MB}
 * each, or a single video in MP4 or WEBM of at most {@code MAX_VIDEO_SIZE_MB} and {@code MAX_VIDEO_DURATION_SECONDS},
 * never both. The limits are read from {@code system_setting} at each check, so a changed limit applies at once.
 *
 * <p>A new file must be a key issued to the caller (its folder and one random key), be stored, and not be attached to another post; a file the post
 * already carries is kept as it was checked. A stored file that breaks a limit is deleted from the storage at once,
 * since nothing will ever attach it. A new video needs a plan with video posting when the caller is a Trader; a video
 * the post already carries stays after the plan ends (BR-43).
 *
 * <p>Rule: BR-43, BR-44, BR-62 ({@code VIDEO_POST}); SRS 3.7.2; MSG29, MSG32; D-98.
 *
 * <p>Reference: OWASP Foundation. <i>File Upload Cheat Sheet</i> (validate the stored file on the server; never trust
 * the client's type or size).
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class MediaRules {

    static final String MAX_IMAGES_PER_POST = "MAX_IMAGES_PER_POST";
    static final String MAX_IMAGE_SIZE_MB = "MAX_IMAGE_SIZE_MB";
    static final String MAX_VIDEO_SIZE_MB = "MAX_VIDEO_SIZE_MB";
    static final String MAX_VIDEO_DURATION_SECONDS = "MAX_VIDEO_DURATION_SECONDS";

    /** The formats of BR-44 as the storage names them. */
    static final List<String> IMAGE_FORMATS = List.of("jpg", "png", "webp");

    static final List<String> VIDEO_FORMATS = List.of("mp4", "webm");

    private static final long MEGABYTE = 1024L * 1024L;

    /** The random part of a key this module issues: a UUID in its canonical form. */
    private static final Pattern ISSUED_KEY =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private static final Logger log = LoggerFactory.getLogger(MediaRules.class);

    private final MediaStorage storage;
    private final PostMediaRepository media;
    private final SystemSettingApi settings;
    private final EntitlementApi entitlements;
    private final CloudinaryProperties properties;

    /** A key under the owner's folder that no file has yet. */
    String newPublicId(UUID owner) {
        return folderOf(owner) + UuidV7.next();
    }

    static List<String> formatsOf(PostMediaType type) {
        return type == PostMediaType.IMAGE ? IMAGE_FORMATS : VIDEO_FORMATS;
    }

    long maxBytes(PostMediaType type) {
        return MEGABYTE * settings.integer(type == PostMediaType.IMAGE ? MAX_IMAGE_SIZE_MB : MAX_VIDEO_SIZE_MB);
    }

    Integer maxDurationSeconds(PostMediaType type) {
        return type == PostMediaType.VIDEO ? settings.integer(MAX_VIDEO_DURATION_SECONDS) : null;
    }

    /** Refuses a new video with MSG29 when a Trader's plan has no video posting (BR-43). */
    void requireVideoPosting(UUID trader) {
        entitlements.requireFeature(trader, Feature.VIDEO_POST);
    }

    /**
     * The files a post is to carry, in the order named, each checked as this class describes.
     *
     * @param owner whose folder new files must be in
     * @param attached the files the post carries now, by key; empty for a new post
     * @param trader whether {@code owner} is a Trader, whose new videos need {@code VIDEO_POST}
     * @throws BusinessException MSG32 ({@code MEDIA_INVALID}), MSG29 or {@code MEDIA_STORAGE_UNAVAILABLE}
     */
    List<CheckedMedia> check(
            UUID owner, List<MediaRequest> requested, Map<String, PostMedia> attached, boolean trader) {
        if (requested.isEmpty()) {
            return List.of();
        }
        long videos = requested.stream()
                .filter(file -> file.mediaType() == PostMediaType.VIDEO)
                .count();
        int maxImages = settings.integer(MAX_IMAGES_PER_POST);
        if (videos > 1 || (videos == 1 && requested.size() > 1) || requested.size() > maxImages) {
            throw invalid(
                    "a post carries up to " + maxImages + " images or one video", maxImages + " images or 1 video");
        }
        Set<String> seen = new HashSet<>();
        List<CheckedMedia> checked = new ArrayList<>();
        for (MediaRequest file : requested) {
            if (!seen.add(file.publicId())) {
                throw invalid("file " + file.publicId() + " is named twice", file.publicId());
            }
            PostMedia kept = attached.get(file.publicId());
            checked.add(
                    kept != null && kept.getMediaType() == file.mediaType()
                            ? new CheckedMedia(file.publicId(), file.mediaType(), null)
                            : new CheckedMedia(file.publicId(), file.mediaType(), fresh(owner, file, trader)));
        }
        return checked;
    }

    private StoredMedia fresh(UUID owner, MediaRequest file, boolean trader) {
        if (!issuedTo(owner, file.publicId()) || media.existsByStoragePublicId(file.publicId())) {
            throw invalid("file " + file.publicId() + " is not a new upload of the caller", file.publicId());
        }
        if (trader && file.mediaType() == PostMediaType.VIDEO) {
            requireVideoPosting(owner);
        }
        StoredMedia stored = describe(file);
        long maxBytes = maxBytes(file.mediaType());
        Integer maxSeconds = maxDurationSeconds(file.mediaType());
        String limit = maxBytes / MEGABYTE + " MB";
        if (!formatsOf(file.mediaType()).contains(stored.format()) || stored.bytes() > maxBytes) {
            discard(file);
            throw invalid(
                    "file " + file.publicId() + " is " + stored.format() + " of " + stored.bytes() + " bytes", limit);
        }
        if (maxSeconds != null && (stored.durationSeconds() == null || stored.durationSeconds() > maxSeconds)) {
            discard(file);
            throw invalid(
                    "video " + file.publicId() + " lasts " + stored.durationSeconds() + " s", maxSeconds + " seconds");
        }
        return stored;
    }

    private StoredMedia describe(MediaRequest file) {
        try {
            return storage.describe(file.mediaType(), file.publicId())
                    .orElseThrow(() -> invalid("nothing is stored under " + file.publicId(), file.publicId()));
        } catch (MediaStorageException failed) {
            throw unavailable(failed);
        }
    }

    // Best effort: the file breaks a limit and nothing will attach it; a failure only leaves it for the storage's
    // sweep.
    private void discard(MediaRequest file) {
        try {
            storage.destroy(file.mediaType(), file.publicId());
        } catch (MediaStorageException failed) {
            log.warn(
                    "Refused file {} could not be deleted from the media storage: {}",
                    file.publicId(),
                    failed.getMessage());
        }
    }

    private String folderOf(UUID owner) {
        return properties.folder() + "/" + owner + "/";
    }

    /**
     * Whether a key has the exact shape {@link #newPublicId(UUID)} gives the owner: the owner's folder and one random
     * key. Anything else — another folder, a path below it, characters a key never has — is not a file this module
     * signed for the owner, and is refused before the storage is asked.
     */
    private boolean issuedTo(UUID owner, String publicId) {
        String folder = folderOf(owner);
        return publicId.startsWith(folder)
                && ISSUED_KEY.matcher(publicId.substring(folder.length())).matches();
    }

    static BusinessException invalid(String detail, String limit) {
        return new BusinessException(ErrorCode.MEDIA_INVALID, detail, limit);
    }

    static BusinessException unavailable(MediaStorageException failed) {
        return new BusinessException(ErrorCode.MEDIA_STORAGE_UNAVAILABLE, failed.getMessage(), (Throwable) failed);
    }
}
