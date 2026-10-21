package com.cryptopilot.community.client;

import com.cryptopilot.community.model.enums.PostMediaType;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The media storage of an environment without a Cloudinary account (outside the prod profile): no file can be signed
 * for or checked, so a post with media is refused and a text post still works. A delete has nothing to delete.
 */
public class UnavailableMediaStorage implements MediaStorage {

    private static final Logger log = LoggerFactory.getLogger(UnavailableMediaStorage.class);

    @Override
    public UploadTicket sign(PostMediaType type, String publicId, List<String> allowedFormats) {
        throw unavailable();
    }

    @Override
    public Optional<StoredMedia> describe(PostMediaType type, String publicId) {
        throw unavailable();
    }

    @Override
    public void destroy(PostMediaType type, String publicId) {
        log.info("No media storage is configured; {} {} was not deleted", type, publicId);
    }

    @Override
    public StoredFilePage list(PostMediaType type, String prefix, String cursor) {
        // Nothing can have been uploaded without a storage, so there is nothing to sweep.
        return StoredFilePage.empty();
    }

    private static MediaStorageException unavailable() {
        return new MediaStorageException("no media storage is configured: set CLOUDINARY_CLOUD_NAME,"
                + " CLOUDINARY_API_KEY and CLOUDINARY_API_SECRET");
    }
}
