package com.cryptopilot.user.client;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import java.util.Optional;
import java.util.UUID;

/**
 * The profile image storage when no Cloudinary account is set (dev and tests): nothing can be uploaded, so no key can be
 * named in a profile; removing an image still works.
 *
 * <p>Rule: D-98, D-99.
 */
public class UnavailableProfileImageStorage implements ProfileImageStorage {

    @Override
    public ProfileImageUploadTicket sign(UUID owner) {
        throw new BusinessException(
                ErrorCode.MEDIA_STORAGE_UNAVAILABLE, "no Cloudinary account is set for profile images");
    }

    @Override
    public boolean issuedTo(UUID owner, String publicId) {
        return false;
    }

    @Override
    public Optional<StoredProfileImage> describe(String publicId) {
        return Optional.empty();
    }

    @Override
    public void destroy(String publicId) {
        // Nothing was ever stored.
    }
}
