package com.cryptopilot.academy.client;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import java.util.Optional;

/**
 * The cover storage when no Cloudinary account is set (dev and tests): nothing can be uploaded, and a lesson keeps
 * whatever https address its administrator gives.
 *
 * <p>Rule: T-114.
 */
public class UnavailableCoverStorage implements CoverStorage {

    @Override
    public CoverUploadTicket sign() {
        throw new BusinessException(ErrorCode.MEDIA_STORAGE_UNAVAILABLE, "no Cloudinary account is set for covers");
    }

    @Override
    public Optional<String> accept(String coverImageUrl) {
        return Optional.ofNullable(coverImageUrl);
    }
}
