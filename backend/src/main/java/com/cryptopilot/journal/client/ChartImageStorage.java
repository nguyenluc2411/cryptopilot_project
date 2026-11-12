package com.cryptopilot.journal.client;

import java.util.Optional;
import java.util.UUID;

/**
 * Where the chart images of trade reviews are kept (SCR-21). The Trader's browser uploads one image straight to the
 * storage with a form this port signs, so the secret never leaves the backend; the review then names the image by its
 * key, and the journal reads back what was stored before it accepts it. Every method but {@link #issuedTo} may throw
 * {@link ChartImageStorageException} when the storage cannot be reached.
 *
 * <p>Rule: BR-40; SRS 3.6.1; D-98.
 */
public interface ChartImageStorage {

    /**
     * A signed form for one new image of {@code owner}.
     *
     * @throws com.cryptopilot.common.exception.BusinessException {@code MEDIA_STORAGE_UNAVAILABLE} without a storage
     */
    ChartImageUploadTicket sign(UUID owner);

    /** Whether the key has the shape {@link #sign} gives {@code owner}: their folder and one random key. */
    boolean issuedTo(UUID owner, String publicId);

    /** The stored image with this key, or empty when nothing was uploaded under it. */
    Optional<StoredChartImage> describe(String publicId);

    /** Deletes the stored image with this key; a key with no image is not an error. */
    void destroy(String publicId);
}
