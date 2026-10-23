package com.cryptopilot.community.client;

import com.cryptopilot.community.model.enums.PostMediaType;
import java.util.List;
import java.util.Optional;

/**
 * The media storage of the images and videos of posts (Cloudinary). Files go from the browser straight to the
 * storage with a form this port signs; the backend then reads what was actually stored before it attaches a file to a
 * post, and deletes files a post no longer carries. The account secret never leaves the implementation.
 *
 * <p>Every method may throw {@link MediaStorageException} when the storage cannot be reached or answers with an
 * error.
 *
 * <p>Rule: BR-44; SRS 3.7.2; D-98.
 */
public interface MediaStorage {

    /** A signed upload form for one file of this type under this key, accepting only these formats. */
    UploadTicket sign(PostMediaType type, String publicId, List<String> allowedFormats);

    /** The stored file with this key, or empty when nothing was uploaded under it. */
    Optional<StoredMedia> describe(PostMediaType type, String publicId);

    /** Deletes the stored file with this key; a key with no file is not an error. */
    void destroy(PostMediaType type, String publicId);

    /**
     * One page of the files of this type stored under the folder {@code prefix}, oldest first, for the sweep of files
     * no post carries.
     *
     * @param cursor the {@link StoredFilePage#nextCursor()} of the page before, or {@code null} for the first page
     */
    StoredFilePage list(PostMediaType type, String prefix, String cursor);
}
