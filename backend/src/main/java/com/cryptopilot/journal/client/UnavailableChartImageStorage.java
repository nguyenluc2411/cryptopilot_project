package com.cryptopilot.journal.client;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import java.util.Optional;
import java.util.UUID;

/**
 * The chart image storage when no Cloudinary account is set (dev and tests): nothing can be uploaded, so no key can be
 * named in a review; removing an image still works.
 *
 * <p>Rule: BR-40; D-98.
 */
public class UnavailableChartImageStorage implements ChartImageStorage {

    @Override
    public ChartImageUploadTicket sign(UUID owner) {
        throw new BusinessException(ErrorCode.MEDIA_STORAGE_UNAVAILABLE, "no Cloudinary account is set for charts");
    }

    @Override
    public boolean issuedTo(UUID owner, String publicId) {
        return false;
    }

    @Override
    public Optional<StoredChartImage> describe(String publicId) {
        return Optional.empty();
    }

    @Override
    public void destroy(String publicId) {
        // Nothing was ever stored.
    }
}
